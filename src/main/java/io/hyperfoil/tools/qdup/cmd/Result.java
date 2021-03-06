package io.hyperfoil.tools.qdup.cmd;

/**
 * Created by wreicher
 */
public class Result {
    public enum Type {next, skip}

    private String result;
    private Type type;

    private Result(String result, Type type) {
        this.result = result;
        this.type = type;
    }

    public String getResult() {
        return result;
    }

    public Type getType() {
        return type;
    }

    public static Result next(String output) {
        return new Result(output, Type.next);
    }

    public static Result skip(String output) {
        return new Result(output, Type.skip);
    }
}
