// SPDX-FileCopyrightText: 2021 The CC: Tweaked Developers
//
// SPDX-License-Identifier: MPL-2.0

package ru.lazyhat.compuktercraft.network.client
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
