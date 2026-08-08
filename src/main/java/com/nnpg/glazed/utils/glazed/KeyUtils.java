package com.nnpg.glazed.utils.glazed;

import org.apache.commons.lang3.StringUtils;
import org.lwjgl.glfw.GLFW;

import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class KeyUtils {

    private static final int MAX_MOUSE_BUTTON = GLFW.GLFW_MOUSE_BUTTON_LAST;

    // built once. old switch made a whole new SecureRandom string every singl lookup
    // vro why was this encrypting keybind names, who is stealing "Left Shift" from u
    private static final Map<Integer, String> KEY_NAMES = Map.ofEntries(
        Map.entry(0, "LMB"),
        Map.entry(1, "RMB"),
        Map.entry(2, "MMB"),
        Map.entry(-1, "Unknown"),
        Map.entry(GLFW.GLFW_KEY_ESCAPE, "Esc"),
        Map.entry(GLFW.GLFW_KEY_GRAVE_ACCENT, "Grave Accent"),
        Map.entry(GLFW.GLFW_KEY_WORLD_1, "World 1"),
        Map.entry(GLFW.GLFW_KEY_WORLD_2, "World 2"),
        Map.entry(GLFW.GLFW_KEY_PRINT_SCREEN, "Print Screen"),
        Map.entry(GLFW.GLFW_KEY_PAUSE, "Pause"),
        Map.entry(GLFW.GLFW_KEY_INSERT, "Insert"),
        Map.entry(GLFW.GLFW_KEY_DELETE, "Delete"),
        Map.entry(GLFW.GLFW_KEY_HOME, "Home"),
        Map.entry(GLFW.GLFW_KEY_PAGE_UP, "Page Up"),
        Map.entry(GLFW.GLFW_KEY_PAGE_DOWN, "Page Down"),
        Map.entry(GLFW.GLFW_KEY_END, "End"),
        Map.entry(GLFW.GLFW_KEY_TAB, "Tab"),
        Map.entry(GLFW.GLFW_KEY_LEFT_CONTROL, "Left Control"),
        Map.entry(GLFW.GLFW_KEY_RIGHT_CONTROL, "Right Control"),
        Map.entry(GLFW.GLFW_KEY_LEFT_ALT, "Left Alt"),
        Map.entry(GLFW.GLFW_KEY_RIGHT_ALT, "Right Alt"),
        Map.entry(GLFW.GLFW_KEY_LEFT_SHIFT, "Left Shift"),
        Map.entry(GLFW.GLFW_KEY_RIGHT_SHIFT, "Right Shift"),
        Map.entry(GLFW.GLFW_KEY_UP, "Arrow Up"),
        Map.entry(GLFW.GLFW_KEY_DOWN, "Arrow Down"),
        Map.entry(GLFW.GLFW_KEY_LEFT, "Arrow Left"),
        Map.entry(GLFW.GLFW_KEY_RIGHT, "Arrow Right"),
        Map.entry(GLFW.GLFW_KEY_APOSTROPHE, "Apostrophe"),
        Map.entry(GLFW.GLFW_KEY_BACKSPACE, "Backspace"),
        Map.entry(GLFW.GLFW_KEY_CAPS_LOCK, "Caps Lock"),
        Map.entry(GLFW.GLFW_KEY_MENU, "Menu"),
        Map.entry(GLFW.GLFW_KEY_LEFT_SUPER, "Left Super"),
        Map.entry(GLFW.GLFW_KEY_RIGHT_SUPER, "Right Super"),
        Map.entry(GLFW.GLFW_KEY_ENTER, "Enter"),
        Map.entry(GLFW.GLFW_KEY_KP_ENTER, "Numpad Enter"),
        Map.entry(GLFW.GLFW_KEY_NUM_LOCK, "Num Lock"),
        Map.entry(GLFW.GLFW_KEY_SPACE, "Space"),
        Map.entry(GLFW.GLFW_KEY_SCROLL_LOCK, "Scroll Lock"),
        Map.entry(GLFW.GLFW_KEY_LEFT_BRACKET, "Left Bracket"),
        Map.entry(GLFW.GLFW_KEY_RIGHT_BRACKET, "Right Bracket"),
        Map.entry(GLFW.GLFW_KEY_SEMICOLON, "Semicolon"),
        Map.entry(GLFW.GLFW_KEY_EQUAL, "Equals"),
        Map.entry(GLFW.GLFW_KEY_BACKSLASH, "Backslash"),
        Map.entry(GLFW.GLFW_KEY_COMMA, "Comma")
    );

    private KeyUtils() {}

    public static CharSequence getKey(int key) {
        String name = KEY_NAMES.get(key);
        if (name != null) return name;

        // f keys are in order so no point listing all 25
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F25) {
            return "F" + (key - GLFW.GLFW_KEY_F1 + 1);
        }

        String keyName = GLFW.glfwGetKeyName(key, 0);
        if (keyName == null) return "None";

        return StringUtils.capitalize(keyName);
    }

    public static boolean isKeyPressed(final int key) {
        if (mc.getWindow() == null) return false;

        // negative keys make glfw spam INVALID_ENUM instead of just saying false
        if (key < 0) return false;

        if (key <= MAX_MOUSE_BUTTON) {
            return GLFW.glfwGetMouseButton(mc.getWindow().handle(), key) == GLFW.GLFW_PRESS;
        }

        return GLFW.glfwGetKey(mc.getWindow().handle(), key) == GLFW.GLFW_PRESS;
    }
}
