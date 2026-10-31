package jabs.consensus.algorithm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guarda estatica do criterio 4: o codigo (sem comentarios e sem literais de texto) de um arquivo de hook bizantino
 * nao pode conter {@code catch (Exception|Throwable|RuntimeException ...)} de corpo vazio nem excecao chamada
 * {@code ignored}. Comentarios sao removidos antes (a prosa que documenta a remocao pode citar o padrao antigo).
 * Detecta o catch vazio com ou sem comentario dentro, em varias linhas e com {@code final}.
 */
public final class GuardaCatch {
    private static final Pattern CATCH_VAZIO = Pattern.compile(
            "catch\\s*\\(\\s*(?:final\\s+)?(?:java\\.lang\\.)?(?:Exception|Throwable|RuntimeException|Error)\\s+\\w+\\s*\\)\\s*\\{\\s*\\}");
    private static final Pattern IGNORED = Pattern.compile("\\bignored\\b");

    static String semComentariosENemLiterais(String fonte) {
        // Remove /* ... */, // ... e literais "..." (nessa ordem de precedencia, varrendo uma vez).
        Pattern p = Pattern.compile("/\\*.*?\\*/|//[^\\n]*|\"(?:\\\\.|[^\"\\\\])*\"", Pattern.DOTALL);
        Matcher m = p.matcher(fonte);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, m.group().startsWith("\"") ? "\"\"" : " ");
        m.appendTail(sb);
        return sb.toString();
    }

    public static void exigeSemCatchEngolido(Path arquivo) throws IOException {
        String codigo = semComentariosENemLiterais(Files.readString(arquivo));
        assertFalse(CATCH_VAZIO.matcher(codigo).find(), arquivo + " engole Exception (catch vazio; lint JB-009)");
        assertFalse(IGNORED.matcher(codigo).find(), arquivo + " tem excecao 'ignored'");
    }

    private GuardaCatch() {}
}
