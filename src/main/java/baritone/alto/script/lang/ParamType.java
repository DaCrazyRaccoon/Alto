/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.alto.script.lang;

/**
 * Declared type of a procedure parameter. {@code item} and {@code block} hold ids (strings) that are checked and
 * normalized ({@code oak_log} becomes {@code minecraft:oak_log}) when the procedure is called.
 */
public enum ParamType implements JsonNamed {
    NUMBER("number", ValueType.NUMBER),
    STRING("string", ValueType.STRING),
    BOOLEAN("boolean", ValueType.BOOLEAN),
    ITEM("item", ValueType.STRING),
    BLOCK("block", ValueType.STRING);

    private final String jsonName;
    private final ValueType valueType;

    ParamType(String jsonName, ValueType valueType) {
        this.jsonName = jsonName;
        this.valueType = valueType;
    }

    @Override
    public String jsonName() {
        return jsonName;
    }

    public ValueType valueType() {
        return valueType;
    }

    /** Whether values must be item/block ids. */
    public boolean isId() {
        return this == ITEM || this == BLOCK;
    }
}
