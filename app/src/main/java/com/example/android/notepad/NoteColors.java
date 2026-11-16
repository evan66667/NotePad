package com.example.android.notepad;

/**
 * Defines the pastel palette used by the sticky-note grid and the editor paper.
 */
public final class NoteColors {

    // Available sticky-note colors, in the same order as R.array.note_color_names.
    public static final int[] VALUES = {
            0xFFFFF3B0, // 柠檬黄
            0xFFC8F7C5, // 薄荷绿
            0xFFC5E4F7, // 天空蓝
            0xFFFFD6E0, // 樱花粉
            0xFFE1D5F5, // 薰衣草紫
            0xFFFFE0B2, // 蜜桃橙
            0xFFFFFFFF, // 纯白
            0xFF37474F  // 深灰
    };

    // Whether a color needs dark text.
    private static final boolean[] DARK_TEXT = {
            true, true, true, true, true, true, true, false
    };

    private NoteColors() {
    }

    /** Clamps a stored color index to the palette. */
    public static int index(int value) {
        return value >= 0 && value < VALUES.length ? value : 0;
    }

    public static int background(int value) {
        return VALUES[index(value)];
    }

    public static int title(int value) {
        return DARK_TEXT[index(value)] ? 0xFF263238 : 0xFFFFFFFF;
    }

    public static int secondary(int value) {
        return DARK_TEXT[index(value)] ? 0xFF546E7A : 0xFFB0BEC5;
    }

    public static int time(int value) {
        return DARK_TEXT[index(value)] ? 0xFF78909C : 0xFF90A4AE;
    }

    public static int pin(int value) {
        return DARK_TEXT[index(value)] ? 0xFFE65100 : 0xFFFFD54F;
    }

    public static int stroke(int value) {
        return DARK_TEXT[index(value)] ? 0x1A000000 : 0x33FFFFFF;
    }

    public static int editorText(int value) {
        return DARK_TEXT[index(value)] ? 0xFF2B3A3A : 0xFFFFFFFF;
    }

    public static int editorLine(int value) {
        return DARK_TEXT[index(value)] ? 0x22000000 : 0x33FFFFFF;
    }
}
