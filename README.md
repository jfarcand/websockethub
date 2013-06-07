A simple [Atmosphere](https://github.com/Atmosphere/atmosphere)'s application that use RabbitMQ to dispatch messages amongst a cluster of WebSocket's Hub.

Can be deployed anywhere WebSockets are supported. Easiest is to bootrap it using [NettoSphere](https://github.com/Atmosphere/nettosphere).

```java
public class NettoSphereBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(Nettosphere.class);

    public static void main(String[] args) throws IOException {
        Config.Builder b = new Config.Builder();
        b.resource(WebSocketHub.class)
         .port(8080)
         .host("127.0.0.1")
         .build();

        Nettosphere s = new Nettosphere.Builder().config(b.build()).build();
        s.start();
        String a = "";

        logger.info("WebSocket HUB started on port {}", 8080);
        logger.info("Type quit to stop the server");
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        while (!(a.equals("quit"))) {
            a = br.readLine();
        }
        System.exit(-1);
    }

}
```
Then from any WebSockets client library (browser, wssh, [wAsync](https://github.com/Atmosphere/wasync)), just issue:

```js
ws://127.0.0.1:8080/id='unique token'
```