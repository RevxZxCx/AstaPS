package emu.grasscutter.utils;

import static org.junit.jupiter.api.Assertions.*;

import emu.grasscutter.ServerResourceFixture;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The ability JSON is bound with {@link JsonUtils#lenientGson}, which uses Gson's identity field
 * naming policy: a JSON key only reaches a field of exactly that name unless the field declares a
 * {@code @SerializedName}. Two keys in AbilityModifierAction were spelled differently from their
 * fields, and both failed silently - no exception, no log, just the field's default.
 */
@ExtendWith(ServerResourceFixture.class)
final class AbilityActionAliasBindingTest {

    @Test
    void monsterIdFromTheJsonReachesTheMonsterIDField() {
        var action = bind("{\"monsterId\":24020102}");

        assertEquals(24020102, action.monsterID);
    }

    @Test
    void theFieldNameSpellingStillBindsSoNoExistingDumpBreaks() {
        assertEquals(24020102, bind("{\"monsterID\":24020102}").monsterID);
    }

    @Test
    void aMissingMonsterIdStaysZeroInsteadOfInventingAMonster() {
        assertEquals(0, bind("{\"summonTag\":1}").monsterID);
    }

    @Test
    void healRatioFromTheJsonReachesTheFieldInsteadOfTheOneDefault() {
        var action = bind("{\"HealRatio\":0.5}");

        assertEquals(0.5f, action.healRatio.get(), 1e-6f);
    }

    @Test
    void theLowerCaseHealRatioSpellingBindsToo() {
        assertEquals(0.25f, bind("{\"healRatio\":0.25}").healRatio.get(), 1e-6f);
    }

    @Test
    void anAbsentHealRatioKeepsTheNeutralDefault() {
        assertEquals(1f, bind("{}").healRatio.get(), 1e-6f);
    }

    @Test
    void theBornBlockArrivesAsARawMapWithTheKeysTheHandlersRead() {
        var action =
                bind(
                        "{\"born\":{\"$type\":\"ConfigBornByGlobalValue\",\"positionKey\":\"SplitPos1\","
                                + "\"offset\":{\"y\":7,\"z\":7},\"onGround\":true}}");

        Map<String, Object> born = action.born;
        assertNotNull(born);
        assertEquals("ConfigBornByGlobalValue", born.get("$type"));
        assertEquals("SplitPos1", born.get("positionKey"));
        assertEquals(Boolean.TRUE, born.get("onGround"));
        assertInstanceOf(Map.class, born.get("offset"));
    }

    private static AbilityModifierAction bind(String json) {
        return JsonUtils.lenientGson.fromJson(json, AbilityModifierAction.class);
    }
}
