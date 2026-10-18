package com.jarvis.core.turn;

/** Who a call would go to, resolved BEFORE asking "Call …? yes or no". */
public final class CallTarget {

    public enum Status { FOUND, NOT_FOUND, NEEDS_PERMISSION }

    public final Status status;
    /** What the user asked for ("ravi"). */
    public final String asked;
    /** How Jarvis says it back ("Ravi Kumar"). */
    public final String label;
    public final String number;

    private CallTarget(Status status, String asked, String label, String number) {
        this.status = status;
        this.asked = asked == null ? "" : asked;
        this.label = label == null ? "" : label;
        this.number = number == null ? "" : number;
    }

    public static CallTarget found(String asked, String label, String number) {
        return new CallTarget(Status.FOUND, asked, label, number);
    }

    public static CallTarget notFound(String asked) {
        return new CallTarget(Status.NOT_FOUND, asked, "", "");
    }

    public static CallTarget needsPermission(String asked) {
        return new CallTarget(Status.NEEDS_PERMISSION, asked, "", "");
    }

    @Override
    public String toString() {
        return status + "{" + label + "}";
    }
}
