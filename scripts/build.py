from pathlib import Path
import subprocess
import os
import zipfile
import urllib.request
import shutil
import json
import hashlib
import sys

ROOT=Path(__file__).resolve().parent.parent
APP=Path(os.environ.get('TASK_APP_DIR', str(ROOT/'app')))
BUILD=APP/'build';BUILD.mkdir(exist_ok=True)
DEPS=APP/'deps';DEPS.mkdir(exist_ok=True)
OUT=ROOT/'outputs';OUT.mkdir(exist_ok=True)
JAVA_HOME=Path(os.environ.get('JAVA_HOME', str(Path.home()/'Library/Java/JavaVirtualMachines/azul-21.0.11/Contents/Home')))
SDK=Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk')))
TOOLS=SDK/'build-tools/36.0.0'
ANDROID=SDK/'platforms/android-36/android.jar'
CACHE=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))/'caches/modules-2/files-2.1'
env=dict(os.environ,JAVA_HOME=str(JAVA_HOME))
def cached(group,artifact,version):
 hits=list((CACHE/group/artifact/version).glob('**/*.jar'))
 assert len(hits)==1,(artifact,hits)
 return hits[0]
def fetch(relative,name):
 p=DEPS/name
 if not p.exists():
  with urllib.request.urlopen('https://repo.maven.apache.org/maven2/'+relative,timeout=30) as r:p.write_bytes(r.read())
 return p
def run(args):
 result=subprocess.run([str(x) for x in args],env=env)
 if result.returncode:raise SystemExit(f'Build step failed: {Path(args[0]).name} (exit {result.returncode})')

stdlib=cached('org.jetbrains.kotlin','kotlin-stdlib','2.1.20')
compiler=[cached('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.20'),stdlib,cached('org.jetbrains.kotlin','kotlin-script-runtime','2.1.20'),cached('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),cached('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),cached('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),cached('org.jetbrains','annotations','13.0')]
rx=fetch('io/reactivex/rxjava2/rxjava/2.2.21/rxjava-2.2.21.jar','rxjava-2.2.21.jar')
timber=fetch('com/jakewharton/timber/timber/4.7.1/timber-4.7.1.aar','timber-4.7.1.aar')
with zipfile.ZipFile(timber) as z:(DEPS/'timber.jar').write_bytes(z.read('classes.jar'))
runtime=[stdlib,rx,cached('org.reactivestreams','reactive-streams','1.0.3'),cached('com.squareup.okhttp3','okhttp','4.12.0'),cached('com.squareup.okio','okio-jvm','3.6.0'),DEPS/'timber.jar',cached('androidx.annotation','annotation-jvm','1.9.1')]
sources=sorted((APP/'src').rglob('*.kt'))
cp=os.pathsep.join(str(x) for x in [ANDROID,*runtime])
print('Compiling focused diagnostic host and transport sources...',flush=True)
if '--package-only' not in sys.argv:
 run([JAVA_HOME/'bin/java','-Xmx2g','-cp',os.pathsep.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xjvm-default=all','-classpath',cp,'-d',BUILD/'classes.jar',*sources])
else:
 assert (BUILD/'classes.jar').exists()
dex=BUILD/'dex';dex.mkdir(exist_ok=True)
print('Packaging Android bytecode...',flush=True)
if '--package-only' not in sys.argv:
 run([TOOLS/'d8','--min-api','32','--lib',ANDROID,'--output',dex,BUILD/'classes.jar',*runtime])
else:
 assert (dex/'classes.dex').exists()
for dep in runtime:
 with zipfile.ZipFile(dep) as archive:
  for name in archive.namelist():
   if Path(name).name.upper().startswith(('LICENSE','NOTICE','COPYING')) and not name.endswith('/'):
    target=APP/'assets/licenses/dependencies'/dep.stem/Path(name).name
    target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.read(name))
(APP/'assets/licenses/dependencies.txt').write_text('Runtime dependencies: Kotlin stdlib 2.1.20, RxJava 2.2.21, reactive-streams 1.0.3, OkHttp 4.12.0, Okio 3.6.0, Timber 4.7.1, AndroidX annotation 1.9.1. See bundled license texts and build-manifest.json.\n')
unsigned=BUILD/'unsigned.apk'
run([TOOLS/'aapt2','link','-o',unsigned,'--manifest',APP/'AndroidManifest.xml','-I',ANDROID,'-A',APP/'assets'])
with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:
 for p in dex.glob('classes*.dex'):z.write(p,p.name)
 for name in ['libgg.so','libxp.so']:
  z.write(ROOT/'vendor/fitbit-air/android/src/main/jniLibs/arm64-v8a'/name,'lib/arm64-v8a/'+name)
aligned=BUILD/'aligned.apk'
run([TOOLS/'zipalign','-f','-P','16','4',unsigned,aligned])
key=Path(os.environ.get('TASK_KEYSTORE', str(BUILD/'diagnostic.keystore')))
if not key.exists():
 run([JAVA_HOME/'bin/keytool','-genkeypair','-keystore',key,'-storepass','android','-keypass','android','-alias','air-lab','-keyalg','RSA','-keysize','2048','-validity','365','-dname','CN=Local Fitbit Air Diagnostic'])
basename=os.environ.get('TASK_APK_NAME','air-signals-0.4')
apk=OUT/(basename+'.apk')
run([TOOLS/'apksigner','sign','--ks',key,'--ks-key-alias','air-lab','--ks-pass','pass:android','--key-pass','pass:android','--out',apk,aligned])
verification=subprocess.check_output([str(TOOLS/'apksigner'),'verify','--print-certs',str(apk)],env=env,text=True)
(OUT/'fitbit-air-lab-signature.txt').write_text(verification)
manifest=subprocess.check_output([str(TOOLS/'aapt2'),'dump','badging',str(apk)],text=True)
assert "name='dev.codex.fitbitairlab'" in manifest
permissions=[x for x in manifest.splitlines() if x.startswith('uses-permission:')]
expected=set(os.environ.get("TASK_PERMISSIONS","BLUETOOTH_CONNECT,FOREGROUND_SERVICE,FOREGROUND_SERVICE_CONNECTED_DEVICE,POST_NOTIFICATIONS,WAKE_LOCK").split(","))
actual={line.split("android.permission.",1)[1].split("'",1)[0] for line in permissions}
assert actual==expected,(actual,expected)
sha=hashlib.sha256(apk.read_bytes()).hexdigest()
(OUT/(basename+'.sha256')).write_text(sha+'  '+apk.name+'\n')
deps=[{'file':p.name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in runtime]
(APP/'build-manifest.json').write_text(json.dumps({'apk_sha256':sha,'dependencies':deps,'permissions':['android.permission.'+p for p in sorted(actual)],'compiler':'Kotlin 2.1.20','sdk':36,'min_sdk':32},indent=2))
with zipfile.ZipFile(OUT/(basename+'-source.zip'),'w',zipfile.ZIP_DEFLATED) as z:
 for folder in [APP/'src',APP/'assets']:
  for p in sorted(folder.rglob('*')):
   if p.is_file():z.write(p,'air-lab/'+str(p.relative_to(APP)))
 for name in ['AndroidManifest.xml','provenance.json','build-manifest.json']:z.write(APP/name,'air-lab/'+name)
 for name in ['libgg.so','libxp.so']:
  z.write(ROOT/'vendor/fitbit-air/android/src/main/jniLibs/arm64-v8a'/name,'fitbit-air/android/src/main/jniLibs/arm64-v8a/'+name)
 z.write(Path(__file__),'scripts/build.py')
print('Built',apk.name,'SHA-256',sha,flush=True)
print('Manifest permissions verified:', ', '.join(sorted(actual)), flush=True)
