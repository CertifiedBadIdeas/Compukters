/*
 * The Compukter Kraft Developers
 *
 * Copyright (C) 2026 Vsevolod Petrov (lazyhat)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ck.mod.network.client
//
// import dan200.computercraft.api.pocket.IPocketUpgrade
// import java.util.Objects
// import net.minecraft.core.Registry
// import net.minecraft.resources.ResourceLocation
//
// /**
// * Syncs turtle and pocket upgrades to the client.
// */
// class UpgradesLoadedMessage : NetworkMessage<ClientNetworkContext?> {
// 	private val turtleUpgrades: MutableMap<String?, UpgradeManager.UpgradeWrapper<TurtleUpgradeSerialiser<*>?, ITurtleUpgrade?>?>
// 	private val pocketUpgrades: MutableMap<String?, UpgradeManager.UpgradeWrapper<PocketUpgradeSerialiser<*>?, IPocketUpgrade?>?>
//
// 	constructor() {
// 		turtleUpgrades = TurtleUpgrades.instance().getUpgradeWrappers()
// 		pocketUpgrades = PocketUpgrades.instance().getUpgradeWrappers()
// 	}
//
// 	constructor(buf: FriendlyByteBuf) {
// 		turtleUpgrades = fromBytes<R?, T?>(buf, TurtleUpgradeSerialiser.registryId())
// 		pocketUpgrades = fromBytes<R?, T?>(buf, PocketUpgradeSerialiser.registryId())
// 	}
//
// 	private fun <R : UpgradeSerialiser<out T?>?, T : UpgradeBase?> fromBytes(
// 		buf: FriendlyByteBuf, registryKey: ResourceKey<Registry<R?>?>?
// 	): MutableMap<String?, UpgradeManager.UpgradeWrapper<R?, T?>?> {
// 		val registry: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = PlatformHelper.get()
// 			.wrap(registryKey)
//
// 		val size: Int = buf.readVarInt()
// 		val upgrades: MutableMap<String?, UpgradeManager.UpgradeWrapper<R?, T?>?> = HashMap<String?, UpgradeManager.UpgradeWrapper<R?, T?>?>(
// 			size
// 		)
// 		for (i in 0 ..< size) {
// 			val id: String = buf.readUtf()
//
// 			val serialiserId: ResourceLocation = buf.readResourceLocation()
// 			val serialiser: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = registry.tryGet(serialiserId)
// 			checkNotNull(serialiser) { "Unknown serialiser " + serialiserId }
//
// 			val upgrade: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = serialiser.fromNetwork(
// 				ResourceLocation(id),
// 				buf
// 			)
// 			val modId: String = buf.readUtf()
//
// 			upgrades.put(id, UpgradeWrapper<R?, T?>(id, upgrade, serialiser, modId))
// 		}
//
// 		return upgrades
// 	}
//
// 	public override fun write(buf: FriendlyByteBuf) {
// 		toBytes<R?, T?>(buf, TurtleUpgradeSerialiser.registryId(), turtleUpgrades)
// 		toBytes<R?, T?>(buf, PocketUpgradeSerialiser.registryId(), pocketUpgrades)
// 	}
//
// 	private fun <R : UpgradeSerialiser<out T?>?, T : UpgradeBase?> toBytes(
// 		buf: FriendlyByteBuf,
// 		registryKey: ResourceKey<Registry<R?>?>?,
// 		upgrades: MutableMap<String?, UpgradeManager.UpgradeWrapper<R?, T?>?>
// 	) {
// 		val registry: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = PlatformHelper.get()
// 			.wrap(registryKey)
//
// 		buf.writeVarInt(upgrades.size)
// 		for (entry in upgrades.entries) {
// 			buf.writeUtf(entry.key)
//
// 			val serialiser: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? = entry.value.serialiser()
// 			val unwrappedSerialiser: UpgradeSerialiser<T?>? = serialiser as UpgradeSerialiser<T?>?
//
// 			buf.writeResourceLocation(Objects.requireNonNull(registry.getKey(serialiser), "Serialiser is not registered!"))
// 			unwrappedSerialiser.toNetwork(buf, entry.value.upgrade())
//
// 			buf.writeUtf(entry.value.modId())
// 		}
// 	}
//
// 	public override fun handle(context: ClientNetworkContext?) {
// 		TurtleUpgrades.instance().loadFromNetwork(turtleUpgrades)
// 		PocketUpgrades.instance().loadFromNetwork(pocketUpgrades)
// 	}
//
// 	public override fun type(): MessageType<UpgradesLoadedMessage?> {
// 		return NetworkMessages.UPGRADES_LOADED
// 	}
// }
