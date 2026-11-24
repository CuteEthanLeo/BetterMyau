package myau.util.ime;

/**
 * A screen that decides for itself whether typed text goes somewhere, so the input method is
 * switched on for it. Screens without this are judged by their focused text fields.
 */
public interface TextInputScreen {
    /** True while what the user types becomes text (and not shortcuts or movement). */
    boolean wantsTextInput();
}
