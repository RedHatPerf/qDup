package perf.qdup.console;

import io.vertx.core.Vertx;
import perf.qdup.Coordinator;
import perf.qdup.Run;
import perf.qdup.cmd.Dispatcher;
import perf.qdup.util.Runner;

public class WebConsole {

    public static Run run;
    public static Dispatcher dispatcher;
    public static Coordinator coordinator;
    private final Vertx vertx = Vertx.vertx();

    public WebConsole(Run run, Dispatcher dispatcher, Coordinator coordinator) {
        this.run = run;
        this.dispatcher = dispatcher;
        this.coordinator = coordinator;
    }

    public void start() throws Exception {
        vertx.deployVerticle(JsonVerticle.class.getName());
        vertx.deployVerticle(GuiVerticle.class.getName());
    }

    public void stop() {
        if (vertx != null) {
            vertx.close();
        }
    }

    public static void main(String[] args) {
        Runner.runExample(GuiVerticle.class);
    }
}
