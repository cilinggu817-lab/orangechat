# HTTP/2 relay compatibility build

Based on OrangeChat 2.2.7, commit `124d2ce1ac9af60e1351e71e604e97364aa9f055`.

## Problem and scope

Some relay requests fail before response headers with
`StreamResetException: stream was reset: PROTOCOL_ERROR`, while the same relay
works with a different client. The shared OrangeChat client enables a 10-second
ping interval for WebSockets; that setting also affects its HTTP/2 connections.
The actual relay failure has not been reproduced with user credentials, so this
change is a transport compatibility workaround, not proof of the server's cause.

OpenAI, Google and Claude providers now use one derived client with HTTP/1.1,
client-initiated pings disabled, and a separate connection pool. SSE streaming
continues to work over HTTP/1.1. Authentication, proxy selection, timeouts,
interceptors and the shared dispatcher are inherited. WebSocket callers keep
their original transport and heartbeat. The separately registered Codex provider
keeps its existing client. No additional retry or generation replay is added.
HTTP/2 multiplexing is unavailable to these three providers in this build.

No conversation, database, memory, attachment, prompt or model-payload code is
changed. No API keys or conversation contents are included in the build.

## Build and tests

```sh
git submodule update --init --depth 1 material3/material-color-utilities
cd web-ui
pnpm install --frozen-lockfile
pnpm run build
cd ..
bash gradlew :ai:testDebugUnitTest --tests me.rerere.ai.provider.ProviderHttpClientTest
bash gradlew :app:assembleHttpcompat -PorangechatAbis=arm64-v8a -PorangechatUniversalApk=false
```

The three regression tests verify client isolation/configuration inheritance,
three delayed requests with intact bodies and SSE response content, and
cancellation before response headers without an added replay. They also pass
as standalone JVM tests against OkHttp/MockWebServer 5.3.2.

The `httpcompat` variant retains release optimization but uses the repository's
test signing key and the distinct package `me.rerere.orangechat.httpcompat`.
Its launcher name is `西瓜瓣·网络测试`. It installs alongside the production app;
it cannot update the author's signed APK or access its private data directly.
This is a test distribution, not an author-signed production release.

For device validation, configure the affected relay/model in the test app and
send several turns, including a slower response. If needed, import a copy of an
exported backup into the test app; keep the production installation in place.
Actual device/relay validation remains required.
