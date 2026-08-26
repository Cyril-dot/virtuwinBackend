package com.example.subscription.model;

/**
 * The AI's prediction for a single pick/game/section found on the scanned
 * betting slip image.
 *
 * New fields (v2):
 *   teamName        — "Home Team vs Away Team" as read from the slip
 *   accuracyPercent — integer 0-100 reflecting model confidence
 *   reason          — one line, ~10 words explaining the prediction
 *
 * Legacy fields (matchLabel, confidence, analysis) are kept and kept in sync
 * so existing callers are not broken.
 */
public class PickPrediction {

    private int    sectionIndex;      // order it appeared on the slip, 1-based
    private String teamName;          // "Home Team vs Away Team" as read off the slip  [NEW]
    private String matchLabel;        // alias of teamName, kept for backwards compat
    private String originalPick;      // the selection printed on the slip, if legible
    private String prediction;        // 1 | X | 2 | unreadable
    private int    accuracyPercent;   // 0-100 model confidence                        [NEW]
    private String confidence;        // "High" / "Medium" / "Low", derived from above
    private String reason;            // one-line ~10-word reasoning                   [NEW]
    private String analysis;          // alias of reason, kept for backwards compat

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

    public PickPrediction() {
    }

    /** Full constructor including all new fields. */
    public PickPrediction(int sectionIndex,
                          String teamName,
                          String originalPick,
                          String prediction,
                          int accuracyPercent,
                          String reason) {
        this.sectionIndex    = sectionIndex;
        this.teamName        = teamName;
        this.matchLabel      = teamName;           // keep in sync
        this.originalPick    = originalPick;
        this.prediction      = prediction;
        this.accuracyPercent = accuracyPercent;
        this.confidence      = deriveConfidence(accuracyPercent);
        this.reason          = reason;
        this.analysis        = reason;             // keep in sync
    }

    /** Legacy constructor — still compiles so existing call sites don't break. */
    public PickPrediction(int sectionIndex,
                          String matchLabel,
                          String originalPick,
                          String prediction,
                          String confidence,
                          String analysis) {
        this.sectionIndex    = sectionIndex;
        this.matchLabel      = matchLabel;
        this.teamName        = matchLabel;         // keep in sync
        this.originalPick    = originalPick;
        this.prediction      = prediction;
        this.confidence      = confidence;
        this.accuracyPercent = deriveAccuracy(confidence);
        this.analysis        = analysis;
        this.reason          = analysis;           // keep in sync
    }

    // ------------------------------------------------------------------
    // Getters & Setters
    // ------------------------------------------------------------------

    public int getSectionIndex() {
        return sectionIndex;
    }

    public void setSectionIndex(int sectionIndex) {
        this.sectionIndex = sectionIndex;
    }

    // ---- teamName (primary) / matchLabel (alias) ----------------------

    public String getTeamName() {
        return teamName;
    }

    /** Setting teamName also updates matchLabel so both are always in sync. */
    public void setTeamName(String teamName) {
        this.teamName   = teamName;
        this.matchLabel = teamName;
    }

    public String getMatchLabel() {
        return matchLabel;
    }

    /** Setting matchLabel also updates teamName so both are always in sync. */
    public void setMatchLabel(String matchLabel) {
        this.matchLabel = matchLabel;
        this.teamName   = matchLabel;
    }

    // ---- originalPick ------------------------------------------------

    public String getOriginalPick() {
        return originalPick;
    }

    public void setOriginalPick(String originalPick) {
        this.originalPick = originalPick;
    }

    // ---- prediction --------------------------------------------------

    public String getPrediction() {
        return prediction;
    }

    public void setPrediction(String prediction) {
        this.prediction = prediction;
    }

    // ---- accuracyPercent (primary) / confidence (alias) --------------

    public int getAccuracyPercent() {
        return accuracyPercent;
    }

    /** Setting accuracyPercent also refreshes the legacy confidence string. */
    public void setAccuracyPercent(int accuracyPercent) {
        this.accuracyPercent = accuracyPercent;
        this.confidence      = deriveConfidence(accuracyPercent);
    }

    public String getConfidence() {
        return confidence;
    }

    /** Setting confidence also updates accuracyPercent so both stay in sync. */
    public void setConfidence(String confidence) {
        this.confidence      = confidence;
        this.accuracyPercent = deriveAccuracy(confidence);
    }

    // ---- reason (primary) / analysis (alias) -------------------------

    public String getReason() {
        return reason;
    }

    /** Setting reason also updates analysis so both are always in sync. */
    public void setReason(String reason) {
        this.reason   = reason;
        this.analysis = reason;
    }

    public String getAnalysis() {
        return analysis;
    }

    /** Setting analysis also updates reason so both are always in sync. */
    public void setAnalysis(String analysis) {
        this.analysis = analysis;
        this.reason   = analysis;
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private static String deriveConfidence(int pct) {
        if (pct >= 80) return "High";
        if (pct >= 60) return "Medium";
        return "Low";
    }

    private static int deriveAccuracy(String confidence) {
        if (confidence == null) return 0;
        return switch (confidence.toLowerCase()) {
            case "high"   -> 85;
            case "medium" -> 65;
            case "low"    -> 45;
            default       -> 0;
        };
    }

    // ------------------------------------------------------------------
    // toString — useful in logs
    // ------------------------------------------------------------------

    @Override
    public String toString() {
        return "PickPrediction{" +
                "#" + sectionIndex +
                ", teams='" + teamName + '\'' +
                ", pred='" + prediction + '\'' +
                ", accuracy=" + accuracyPercent + "%" +
                ", reason='" + reason + '\'' +
                '}';
    }
}