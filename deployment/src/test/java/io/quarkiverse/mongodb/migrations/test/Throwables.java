package io.quarkiverse.mongodb.migrations.test;

final class Throwables {

    private Throwables() {
    }

    /**
     * The messages of a failure and all its causes, one per line, to assert on failures wrapped by Quarkus.
     */
    static String messages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
