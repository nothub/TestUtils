package lol.hub.testutils;

/** The values of a dimension's type that no vanilla command reports, read from the running server. */
public record DimensionType(String id, int monsterSpawnBlockLightLimit, boolean hasEnderDragonFight) {
}
