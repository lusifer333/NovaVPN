/**
 * Room-based local persistence for NovaVPN.
 *
 * This module contains Room [androidx.room.Entity] classes,
 * [androidx.room.Dao] interfaces, and the [com.novavpn.storage.room.NovaDatabase]
 * definition. All database access flows through DAOs; entities are
 * converted to/from domain models in the `:core:data` module.
 */
package com.novavpn.storage.room
