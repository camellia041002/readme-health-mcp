.PHONY: test test-backend test-web test-android build-android docker-build

test: test-backend test-web test-android

test-backend:
	uv run --with-requirements backend/requirements.txt pytest

test-web:
	cd frontend && npm ci && npm test -- --run && npm run build

test-android:
	cd android && ANDROID_HOME="$${ANDROID_HOME:-/opt/android-sdk}" ./gradlew :app:testDebugUnitTest

build-android:
	cd android && ANDROID_HOME="$${ANDROID_HOME:-/opt/android-sdk}" ./gradlew :app:assembleDebug

docker-build:
	docker build -t healthquery:dev .
