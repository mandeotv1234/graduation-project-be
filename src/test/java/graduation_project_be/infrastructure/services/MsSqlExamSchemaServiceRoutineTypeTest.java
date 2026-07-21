package graduation_project_be.infrastructure.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MsSqlExamSchemaServiceRoutineTypeTest {

    @Test
    void formatsCharacterLengthsIncludingMax() {
        assertEquals("VARCHAR(50)", type("varchar", 50, null, null, null));
        assertEquals("NVARCHAR(MAX)", type("nvarchar", -1, null, null, null));
    }

    @Test
    void formatsNumericPrecisionAndScale() {
        assertEquals("DECIMAL(15,2)", type("decimal", null, 15, 2, null));
        assertEquals("FLOAT(24)", type("float", null, 24, null, null));
    }

    @Test
    void formatsDatetimePrecisionAndLeavesBaseTypesUntouched() {
        assertEquals("DATETIME2(3)", type("datetime2", null, null, null, 3));
        assertEquals("INT", type("int", null, 10, 0, null));
    }

    private String type(
            String dataType,
            Integer characterLength,
            Integer numericPrecision,
            Integer numericScale,
            Integer datetimePrecision) {
        return MsSqlExamSchemaService.formatRoutineDataType(
                dataType, characterLength, numericPrecision, numericScale, datetimePrecision);
    }
}
