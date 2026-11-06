# Third-party notices

Air Signals includes open-source transport code and native libraries. Their original license notices and source headers are retained.

| Component | Source / pin | License |
| --- | --- | --- |
| Fitbit Air transport | [ThoreKoritzius/fitbit-air](https://github.com/ThoreKoritzius/fitbit-air), `5f76f5f8d48d1dab420feeebc6ac87f52be78ba4` | [MIT](app/assets/licenses/LICENSE) |
| Golden Gate core and Kotlin bindings | [Google-Health-API/golden-gate](https://github.com/Google-Health-API/golden-gate), `b8a6f2552e12aaf92c1549dc88a41c9b7e73f1aa` | [Apache-2.0](app/assets/licenses/LICENSE-APACHE-2.0), [NOTICE](app/assets/licenses/third_party/golden-gate/NOTICE) |
| Mbed TLS | [Mbed-TLS/mbedtls](https://github.com/Mbed-TLS/mbedtls), 2.28.10 / `2fc8413bfcb51354c8e679141b17b3f1a5942561` | [Apache-2.0 option](app/assets/licenses/third_party/mbedtls/LICENSE) |
| lwIP | [lwIP](https://savannah.nongnu.org/projects/lwip/), Golden Gate's upstream submodule pin | [BSD-3-Clause](app/assets/licenses/third_party/lwip/COPYING) |

The ARM64 `libgg.so` and `libxp.so` files under [vendor/fitbit-air](vendor/fitbit-air/android/src/main/jniLibs/arm64-v8a/) are redistributed from the pinned Fitbit Air project. The public source and native build instructions are in that upstream project; this repository does not rebuild the native libraries.

Kotlin, AndroidX/Compose, RxJava, OkHttp/Okio, and Timber are resolved through the pinned Gradle dependencies. The included dependency notices are under [app/assets/licenses](app/assets/licenses/); the Gradle wrapper carries its own Apache-2.0 headers. License information from the original upstream distribution is also retained in [its notice file](app/assets/licenses/THIRD_PARTY_NOTICES.md), whose example/Flutter paths refer to that upstream project.
