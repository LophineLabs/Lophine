package me.earthme.luminol.utils.dialog;

import com.mojang.datafixers.util.Pair;
import net.minecraft.commands.functions.StringTemplate;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.*;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.server.dialog.action.CommandTemplate;
import net.minecraft.server.dialog.action.ParsedTemplate;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.TextInput;
import org.jetbrains.annotations.NotNull;
import org.json.simple.JSONObject;

import java.util.*;

public class DialogUtil {
    public static Holder<Dialog> createHolder(String title, Map<String, Pair<Object, String>> map, String commandPrefix) {
        return transformToHolder(
                createDialog(title, map, commandPrefix)
        );
    }

    public static Holder<Dialog> createHolder(String title, List<String> list) {
        return transformToHolder(
                createDialog(title, list)
        );
    }

    public static Holder<Dialog> transformToHolder(Dialog dialog) {
        return Holder.direct(dialog);
    }

    public static MultiActionDialog createDialog(String title, List<String> options) {
        DialogBuilder builder = new DialogBuilder();
        for (String option : options) {
            builder.addButton(
                    createButton(
                            Component.translatable(option),
                            300,
                            Optional.empty()
                    ));
        }

        builder.setTitle(title)
                .setPause(false)
                .setColumns(1);

        return builder.build();
    }

    public static MultiActionDialog createDialog(String title, Map<String, Pair<Object, String>> map, String commandPrefix) {
        return addInputs(map, commandPrefix, new DialogBuilder())
                .setTitle(title)
                .setPause(false)
                .setColumns(1)
                .build();
    }

    public static DialogBuilder addInputs(Map<String, Pair<Object, String>> map, String commandPrefix, @NotNull DialogBuilder builder) {
        boolean hasInput = false;
        JSONObject valueBuilder = new JSONObject();
        Set<String> usedKeys = new HashSet<>();
        int keyCounter = 0;

        for (Map.Entry<String, Pair<Object, String>> entry : map.entrySet()) {
            Object value = entry.getValue().getFirst();
            String label = entry.getKey();
            String key = sanitizeKey(label);
            String comment = entry.getValue().getSecond();

            String originalKey = key;
            while (usedKeys.contains(key)) {
                key = originalKey + "_" + (++keyCounter);
            }
            usedKeys.add(key);

            valueBuilder.put(label, "$(" + key + ")");

            if (comment != null && !comment.isEmpty()) {
                String addition = "Any edit in this text input will not save to file.\n" + comment;
                String _label = "Additional information of " + label;
                String _key = sanitizeKey(_label);
                String _originalKey = _key;
                while (usedKeys.contains(_key)) {
                    _key = _originalKey + "_" + (++keyCounter);
                }
                usedKeys.add(_key);
                builder.addInput(
                        createTextInput(
                                _label,
                                _key,
                                addition,
                                300,
                                true,
                                2147483647,
                                new TextInput.MultilineOptions(
                                        Optional.of(1000),
                                        Optional.of(
                                                (int) (20 * (addition.lines().count() + 1)
                                                )
                                        )
                                )
                        )
                );
            }

            switch (value) {
                case Boolean boolValue -> {
                    Input checkbox = createCheckbox(label, key, boolValue, "true", "false");
                    builder.addInput(checkbox);
                }
                case String stringValue -> {
                    Input textbox = createTextInput(label, key, stringValue, 300, true, 2147483647, null);
                    builder.addInput(textbox);
                }
                case Number numberValue -> {
                    Input numberInput = createTextInput(label, key, numberValue.toString(), 300, true, 2147483647, null);
                    builder.addInput(numberInput);
                }
                default -> {
                }
            }

            hasInput = true;
        }
        String raw = commandPrefix + valueBuilder.toJSONString() + "$(missing)";
        StringTemplate template = StringTemplate.fromString(raw);
        CommandTemplate confirmTemplate = new CommandTemplate(new ParsedTemplate(raw, template));
        if (hasInput) {
            builder.addButton(createButton(
                            Component.translatable("Confirm"),
                            300,
                            Optional.of(confirmTemplate)
                    ))
                    .addButton(createButton(
                            Component.translatable("Cancel"),
                            300,
                            Optional.empty()
                    ));
        }

        return builder;
    }

    public static Input createCheckbox(String label, String key, boolean value, String trueText, String falseText) {
        return new Input(key, new BooleanInput(
                Component.translatable(label),
                value,
                trueText,
                falseText
        ));
    }

    public static Input createTextInput(String label, String key, String value, int width, boolean labelVisible, int maxLength, TextInput.MultilineOptions multilineOptions) {
        return new Input(key, new TextInput(
                width,
                Component.translatable(label),
                labelVisible,
                value,
                maxLength,
                Optional.ofNullable(multilineOptions)
        ));
    }

    // TODO: number input is not work now
    public static Input createNumberInput(String label, String key, Number value, NumberRangeInput.RangeInfo rangeInfo) {
        return new Input(key, new NumberRangeInput(
                300,
                Component.translatable(label),
                value.getClass().getName(),
                rangeInfo
        ));
    }

    public static ActionButton createButton(Component key, int width, Optional<Action> action) {
        CommonButtonData buttonData = new CommonButtonData(
                key,
                width
        );
        return new ActionButton(buttonData, action);
    }

    /**
     * Calculate the display width of a string (Chinese characters have width 2, English characters have width 1)
     *
     * @param text The text to calculate width for
     * @return The display width
     */
    public static int getTextDisplayWidth(String text) {
        int width = 0;
        for (char c : text.toCharArray()) {
            // Chinese character range
            if (c >= '\u4e00' && c <= '\u9fff') {
                width += 2;
            } else {
                width += 1;
            }
        }
        return width;
    }

    /**
     * Split text into words, preserving spaces and line breaks
     *
     * @param text The text to split
     * @return List of words with their separators
     */
    private static List<String> splitIntoWords(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder currentWord = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (Character.isWhitespace(c)) {
                // If we have accumulated a word, add it to list
                if (!currentWord.isEmpty()) {
                    words.add(currentWord.toString());
                    currentWord = new StringBuilder();
                }
                // Add whitespace as separate "word"
                words.add(String.valueOf(c));
            } else {
                // Accumulate non-whitespace characters
                currentWord.append(c);
            }
        }

        // Add the last word if exists
        if (!currentWord.isEmpty()) {
            words.add(currentWord.toString());
        }

        return words;
    }

    /**
     * Automatically wrap text by words based on display width
     *
     * @param text     Original text
     * @param maxWidth Maximum display width per line
     * @return Processed text with appropriate line breaks
     */
    public static String wrapTextByWordsAndWidth(String text, int maxWidth) {
        // If text is null or width is within limit, return as is
        if (text == null || getTextDisplayWidth(text) <= maxWidth) {
            return text;
        }

        List<String> words = splitIntoWords(text);
        StringBuilder wrappedText = new StringBuilder();
        StringBuilder currentLine = new StringBuilder();
        int currentWidth = 0;

        for (String word : words) {
            int wordWidth = getTextDisplayWidth(word);

            // Handle explicit line breaks
            if (word.contains("\n")) {
                // Add current line content
                wrappedText.append(currentLine);
                // Add the word containing newline
                wrappedText.append(word);
                // Reset for next line
                currentLine = new StringBuilder();
                currentWidth = 0;
                continue;
            }

            // Handle whitespace characters
            if (word.matches("\\s+")) {
                // If adding space would exceed limit, wrap to next line
                if (currentWidth + wordWidth > maxWidth && !currentLine.toString().trim().isEmpty()) {
                    wrappedText.append(currentLine.toString().trim()).append("\n");
                    currentLine = new StringBuilder();
                    currentWidth = 0;
                    // Only add space if it's not leading whitespace on new line
                    if (!word.equals(" ")) {
                        currentLine.append(word);
                        currentWidth = wordWidth;
                    }
                } else {
                    currentLine.append(word);
                    currentWidth += wordWidth;
                }
                continue;
            }

            // Handle regular words
            // If adding word would exceed limit, wrap to next line
            if (currentWidth + wordWidth > maxWidth && !currentLine.toString().trim().isEmpty()) {
                wrappedText.append(currentLine.toString().trim()).append("\n");
                currentLine = new StringBuilder();
                currentWidth = 0;
            }

            currentLine.append(word);
            currentWidth += wordWidth;
        }

        // Append the last line if not empty
        if (!currentLine.toString().trim().isEmpty()) {
            wrappedText.append(currentLine.toString().trim());
        }

        return wrappedText.toString();
    }

    /**
     * Automatically wrap text based on display width
     *
     * @param text     Original text
     * @param maxWidth Maximum display width
     * @return Processed text with line breaks
     */
    public static String wrapTextByDisplayWidth(String text, int maxWidth) {
        // If text is null or width is within limit, return as is
        if (text == null || getTextDisplayWidth(text) <= maxWidth) {
            return text;
        }

        StringBuilder wrappedText = new StringBuilder();
        StringBuilder currentLine = new StringBuilder();
        int currentWidth = 0;

        for (char c : text.toCharArray()) {
            // Determine character width (2 for Chinese, 1 for others)
            int charWidth = (c >= '\u4e00' && c <= '\u9fff') ? 2 : 1;

            if (currentWidth + charWidth > maxWidth && !currentLine.isEmpty()) {
                wrappedText.append(currentLine.toString().trim()).append("\n");
                currentLine = new StringBuilder();
                currentWidth = 0;
            }

            currentLine.append(c);
            currentWidth += charWidth;
        }

        if (!currentLine.isEmpty()) {
            wrappedText.append(currentLine.toString().trim());
        }

        return wrappedText.toString();
    }

    private static String sanitizeKey(String originalKey) {
        if (originalKey == null || originalKey.isEmpty()) {
            return "key_" + System.currentTimeMillis(); // generate a unique key
        }

        StringBuilder sanitized = new StringBuilder();

        char firstChar = originalKey.charAt(0);
        if (Character.isDigit(firstChar)) {
            sanitized.append("_").append(firstChar);
        } else if (isValidKeyChar(firstChar)) {
            sanitized.append(firstChar);
        } else {
            sanitized.append("_");
        }

        for (int i = 1; i < originalKey.length(); i++) {
            char c = originalKey.charAt(i);
            if (isValidKeyChar(c)) {
                sanitized.append(c);
            } else {
                sanitized.append("_");
            }
        }

        String result = sanitized.toString();
        if (result.isEmpty() || Character.isDigit(result.charAt(0))) {
            result = "key_" + result;
        }

        return result;
    }

    private static boolean isValidKeyChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    public static class DialogBuilder {
        String title = "";
        Optional<Component> externalTitle = Optional.empty();
        boolean canCloseWithEscape = true;
        boolean pause = true;
        int actionClose = 0;
        int columns = 2;
        List<ActionButton> buttons = new ArrayList<>();
        List<Input> inputs = new ArrayList<>();
        List<DialogBody> bodies = new ArrayList<>();
        Optional<ActionButton> exitButton = Optional.empty();

        public DialogBuilder setTitle(String title) {
            this.title = title;
            return this;
        }

        public DialogBuilder setExternalTitle(Component externalTitle) {
            this.externalTitle = Optional.of(externalTitle);
            return this;
        }

        public DialogBuilder setCanCloseWithEscape(boolean canCloseWithEscape) {
            this.canCloseWithEscape = canCloseWithEscape;
            return this;
        }

        public DialogBuilder setPause(boolean pause) {
            this.pause = pause;
            return this;
        }

        // 0 for close, 1 for none, 2 for wait
        public DialogBuilder setActionClose(int actionClose) {
            this.actionClose = actionClose;
            return this;
        }

        public DialogBuilder setColumns(int columns) {
            this.columns = columns;
            return this;
        }

        public DialogBuilder addButton(ActionButton button) {
            this.buttons.add(button);
            return this;
        }

        public int getButtonCount() {
            return this.buttons.size();
        }

        public DialogBuilder addInput(Input input) {
            this.inputs.add(input);
            return this;
        }

        public int getInputCount() {
            return this.inputs.size();
        }

        public DialogBuilder addBody(DialogBody body) {
            this.bodies.add(body);
            return this;
        }

        public DialogBuilder setExitButton(ActionButton exitButton) {
            this.exitButton = Optional.of(exitButton);
            return this;
        }

        public MultiActionDialog build() {
            CommonDialogData data = new CommonDialogData(
                    Component.translatable(title),
                    externalTitle,
                    canCloseWithEscape,
                    pause,
                    DialogAction.values()[actionClose], // if paused you must do something
                    bodies,
                    inputs
            );
            return new MultiActionDialog(data, buttons, exitButton, columns);
        }
    }
}
