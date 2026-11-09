package io.github.evyuel.postgresmcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.evyuel.postgresmcp.dto.DiagnosticsModels.Status;
import io.github.evyuel.postgresmcp.exception.DiagnosticException;
import org.junit.jupiter.api.Test;

class SqlSafetyValidatorTest {
    @Test
    void acceptsSelectWithQuotedSemicolonsAndOneTrailingSemicolon() {
        String sql = "/* select ; */ WITH x AS (SELECT ';'::text) SELECT * FROM x; -- trailing";
        assertThat(SqlSafetyValidator.validate(sql, 1024)).isEqualTo(
            "/* select ; */ WITH x AS (SELECT ';'::text) SELECT * FROM x");
    }

    @Test
    void acceptsDollarQuotedLiteral() {
        assertThat(SqlSafetyValidator.validate("SELECT $$a;b$$", 1024)).isEqualTo("SELECT $$a;b$$");
        assertThat(SqlSafetyValidator.validate("SELECT E'a\\';b'", 1024)).isEqualTo("SELECT E'a\\';b'");
    }

    @Test
    void rejectsMultipleStatementsAndDmlCtes() {
        assertThatThrownBy(() -> SqlSafetyValidator.validate("SELECT 1; SELECT 2", 1024))
            .isInstanceOf(DiagnosticException.class)
            .extracting(exception -> ((DiagnosticException) exception).getStatus())
            .isEqualTo(Status.NON_SELECT_STATEMENT);
        assertThatThrownBy(() -> SqlSafetyValidator.validate(
            "WITH changed AS (UPDATE app.t SET x = 1 RETURNING *) SELECT * FROM changed", 1024))
            .isInstanceOf(DiagnosticException.class);
    }

    @Test
    void rejectsSelectIntoAndOversizeUtf8() {
        assertThatThrownBy(() -> SqlSafetyValidator.validate("SELECT 1 INTO temp_table", 1024))
            .isInstanceOf(DiagnosticException.class);
        assertThatThrownBy(() -> SqlSafetyValidator.validate("SELECT 'ж'", 5))
            .isInstanceOf(DiagnosticException.class)
            .extracting(exception -> ((DiagnosticException) exception).getStatus())
            .isEqualTo(Status.INVALID_ARGUMENT);
    }
}
