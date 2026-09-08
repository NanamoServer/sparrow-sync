package net.momirealms.sparrow.sync.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

@ApiStatus.Internal
public final class ChatTextUtils {
    private ChatTextUtils() {
    }

    // 默认字体的 ASCII 字形宽度含右侧间距, 其他字符按全角宽度估算.
    public static int width(@NotNull String text) {
        int width = 0;
        int length = text.length();
        for (int i = 0; i < length;) {
            int character = text.codePointAt(i);
            width += switch (character) {
                case '!', '\'', ',', '.', ':', ';', 'i', '|' -> 2;
                case '`', 'l' -> 3;
                case ' ', '"', '(', ')', '*', 'I', '[', ']', 't', '{', '}' -> 4;
                case '<', '>', 'f', 'k' -> 5;
                case '@', '~' -> 7;
                default -> character < 128 ? 6 : 9;
            };
            i += Character.charCount(character);
        }
        return width;
    }

    // 选择可用 4 / 5 像素空格补齐的最小公共宽度, 最多比最宽行增加 12 像素.
    public static int alignedWidth(@NotNull int[] widths) {
        int target = 0;
        int size = widths.length;
        for (int i = 0; i < size; i++) {
            target = Math.max(target, widths[i]);
        }
        for (int i = 0; i < size;) {
            int padding = target - widths[i];
            if (padding < padding % 4 * 5) {
                target++;
                i = 0;
            } else {
                i++;
            }
        }
        return target;
    }

    // 普通空格宽 4 像素, 粗体空格宽 5 像素; pixels 由 alignedWidth 与行宽之差给出.
    @NotNull
    public static Component padding(int pixels) {
        int boldSpaces = pixels % 4;
        int spaces = (pixels - boldSpaces * 5) / 4;
        return Component.text(" ".repeat(spaces)).decoration(TextDecoration.BOLD, false)
                .append(Component.text(" ".repeat(boldSpaces)).decoration(TextDecoration.BOLD, true));
    }
}
