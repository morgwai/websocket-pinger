# Servlet utils

Some helpful classes when developing servlets.<br/>
<br/>
**latest release: 3.1**<br/>
[javax flavor](https://search.maven.org/artifact/pl.morgwai.base/servlet-utils/3.1-javax/jar)
([javadoc](https://javadoc.io/doc/pl.morgwai.base/servlet-utils/3.1-javax)) - supports Websocket `1.1` API<br/>
[jakarta flavor](https://search.maven.org/artifact/pl.morgwai.base/servlet-utils/3.1-jakarta/jar)
([javadoc](https://javadoc.io/doc/pl.morgwai.base/servlet-utils/3.1-jakarta)) - supports Websocket `2.0.0` to at least `2.1.1` APIs


## MAIN USER CLASSES

For now just 1 class: a simple utility service that automatically pings and handles pongs from websocket connections: [WebsocketPingerService](src/main/java/pl/morgwai/base/servlet/utils/WebsocketPingerService.java) (can be used both on a server and a client side).
