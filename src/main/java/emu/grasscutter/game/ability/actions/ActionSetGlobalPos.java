package emu.grasscutter.game.ability.actions;

import com.google.protobuf.ByteString;
import emu.grasscutter.Grasscutter;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.game.ability.Ability;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.world.Position;
import java.util.Map;

/**
 * Writes an ability's global <em>position</em>.
 *
 * <p>These keys are what a summon's born block reads back: the Perpetual Mechanical Array's split
 * writes {@code SplitPos1..4} as the boss's own position plus a small offset, and each minion's
 * Summon then asks for one of them by name. Without this handler the keys are never written, and the
 * only position left for the summon is the one packed in the notify - which is empty, because proto3
 * does not put zero-valued fields on the wire - so every minion spawned at the world origin,
 * thousands of units from the boss.
 *
 * <p>Only {@code ConfigBornBySelf} is resolved, which is what the world bosses use. Any other born
 * type is logged and skipped rather than guessed at.
 */
@AbilityAction(AbilityModifierAction.Type.SetGlobalPos)
public final class ActionSetGlobalPos extends AbilityActionHandler {

    @Override
    public boolean execute(
            Ability ability, AbilityModifierAction action, ByteString abilityData, GameEntity target) {
        if (target == null || target.getScene() == null) return true;
        if (action.key == null) return true;

        Map<String, Object> born = action.born;
        if (born == null) return true;

        String type = String.valueOf(born.get("$type"));
        if (!"ConfigBornBySelf".equals(type)) {
            Grasscutter.getLogger()
                    .debug("[SetGlobalPos] unsupported born {} for key {}", type, action.key);
            return true;
        }

        Position pos = new Position(target.getPosition());
        String abilityName = ability == null ? "<unknown>" : ability.getData().abilityName;
        applyOffset(pos, born.get("offset"), Boolean.TRUE.equals(born.get("onGround")), abilityName);
        target.getGlobalAbilityPositions().put(action.key, pos);
        Grasscutter.getLogger().debug("[SetGlobalPos] {} = {} (born {})", action.key, pos, type);
        return true;
    }

    /**
     * Shift the caster's position by the born block's offset.
     *
     * <p><b>What is established.</b> Under {@code onGround} the offset's {@code y} is a <em>lateral</em>
     * component, not height. That is not our guess from one boss, it is the shape of the data: in the
     * 472 monster ability configs, 674 of the 1243 {@code onGround:true} born blocks that carry an
     * offset spell it with {@code y}, and some of those are mirrored pairs around an identical
     * {@code x}/{@code z} - the Pyro Abyss Mage alone has {@code {x:1, z:2, y:+3.464}} and
     * {@code {x:1, z:2, y:-3.464}}. Read as height with {@code onGround} dropping it, every such pair
     * collapses onto one point, which no designer authors; read as lateral they are two distinct ground
     * positions. The Array's four split corners {@code {z:+/-7, y:+/-7}} are the same thing at four
     * signs, and reading {@code y} as height there is what put two of the four minions under the
     * terrain and culled them.
     *
     * <p><b>What is not.</b> Which horizontal axis the {@code y} folds into. The Array only tells us it
     * is orthogonal to {@code z}, so we take the axis the block has not already used, which keeps the
     * Array's corners a proper square. When both {@code x} and {@code z} are already present we cannot
     * tell (a mirrored {@code y} could belong to either); we fold into {@code x} and log it, so a wrong
     * placement on such a monster is at least traceable instead of looking like an arbitrary offset.
     *
     * <p>{@code alongGround} and {@code onGroundRaycastUpDist} are not modelled: the caster's own height
     * is the ground the client snaps to.
     */
    @SuppressWarnings("unchecked")
    static void applyOffset(Position pos, Object offset, boolean onGround, String abilityName) {
        if (!(offset instanceof Map)) return;
        Map<String, Object> off = (Map<String, Object>) offset;
        pos.setX(pos.getX() + asFloat(off.get("x")));
        pos.setZ(pos.getZ() + asFloat(off.get("z")));
        float dy = asFloat(off.get("y"));
        if (!onGround) {
            pos.setY(pos.getY() + dy);
            return;
        }
        boolean xUsed = off.containsKey("x"), zUsed = off.containsKey("z");
        if (xUsed && zUsed) {
            Grasscutter.getLogger()
                    .debug(
                            "[SetGlobalPos] {} has onGround offset with x, y and z; folding y into x,"
                                    + " which the data alone cannot justify",
                            abilityName);
        }
        if (xUsed && !zUsed) {
            pos.setZ(pos.getZ() + dy);
        } else {
            pos.setX(pos.getX() + dy);
        }
    }

    private static float asFloat(Object value) {
        return value instanceof Number number ? number.floatValue() : 0f;
    }
}
