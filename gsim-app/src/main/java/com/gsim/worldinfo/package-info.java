/**
 * WorldInformation memory model — the single source of truth for world state.
 *
 * <p>Disk layout: worlds/{worldId}/nodes/nXXXX.json (incremental snapshots)
 * loaded on startup into one WorldInformation instance.
 */
package com.gsim.worldinfo;
