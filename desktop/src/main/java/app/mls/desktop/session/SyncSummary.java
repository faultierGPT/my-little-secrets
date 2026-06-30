package app.mls.desktop.session;

/** Outcome of a sync round, for a concise status line ("Synced · 2 pulled, 1 pushed"). */
public record SyncSummary(int pulled, int pushed, int deletedPushed, int conflicts, int keptBoth) {

    public boolean changedAnything() {
        return pulled > 0 || pushed > 0 || deletedPushed > 0;
    }

    public String describe() {
        if (conflicts > 0) {
            return "Synced · " + pulled + " in, " + (pushed + deletedPushed) + " out, " + conflicts + " merged";
        }
        if (!changedAnything()) {
            return "Up to date";
        }
        return "Synced · " + pulled + " in, " + (pushed + deletedPushed) + " out";
    }
}
