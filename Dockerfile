# 机器上有 Docker 就行（镜像自带 Android SDK 与 gradle）：
#   docker build -t qqbq-export . && docker run --rm -v "$PWD/out:/out" qqbq-export
# 产物：./out/app-release.apk
FROM ghcr.io/cirruslabs/android-sdk:34

WORKDIR /src
COPY . .

RUN gradle --no-daemon --stacktrace assembleRelease \
 && mkdir -p /out \
 && cp app/build/outputs/apk/release/app-release.apk /out/app-release.apk

CMD ["ls", "-l", "/out"]
