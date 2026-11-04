"""Build the current Compose app using the checked-in Gradle wrapper."""
from pathlib import Path
import subprocess
root = Path(__file__).resolve().parent.parent
raise SystemExit(subprocess.call([str(root / "gradlew"), ":app:assembleDebug"], cwd=root))
