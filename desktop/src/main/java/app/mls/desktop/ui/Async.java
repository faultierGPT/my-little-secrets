package app.mls.desktop.ui;

import javafx.application.Platform;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs blocking {@code Vault} operations off the JavaFX Application Thread, then delivers the result
 * (or failure) back ON it. A single-threaded executor serializes vault calls, so network/crypto work
 * never overlaps and never freezes the UI.
 */
public final class Async {

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mls-vault");
        t.setDaemon(true);
        return t;
    });

    private Async() {
    }

    /** Run a value-producing task; {@code onSuccess}/{@code onError} run on the FX thread. */
    public static <T> void run(Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        POOL.submit(() -> {
            try {
                T result = work.get();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Throwable e) {
                Platform.runLater(() -> onError.accept(e));
            }
        });
    }

    /** Run a side-effecting task; callbacks run on the FX thread. */
    public static void run(Runnable work, Runnable onSuccess, Consumer<Throwable> onError) {
        run(() -> {
            work.run();
            return null;
        }, ignored -> onSuccess.run(), onError);
    }

    public static void shutdown() {
        POOL.shutdownNow();
    }
}
