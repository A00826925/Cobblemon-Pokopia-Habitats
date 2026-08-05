package com.pokopia.registry

import com.pokopia.Pokopia
import com.pokopia.item.HabitatScannerItem
import dev.architectury.registry.registries.DeferredRegister
import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

object PokopiaItems {
    val ITEMS: DeferredRegister<Item> = DeferredRegister.create(Pokopia.MOD_ID, Registries.ITEM)
    val TABS: DeferredRegister<CreativeModeTab> = DeferredRegister.create(Pokopia.MOD_ID, Registries.CREATIVE_MODE_TAB)

    val HABITAT_SCANNER: RegistrySupplier<HabitatScannerItem> = ITEMS.register("habitat_scanner") {
        HabitatScannerItem(Item.Properties().stacksTo(1))
    }

    val POKOPIA_TAB: RegistrySupplier<CreativeModeTab> = TABS.register("pokopia") {
        // Vanilla builder signature (NeoForge adds a no-arg overload; common compiles
        // against unpatched Minecraft, so pass the Row/column explicitly).
        CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
            .title(Component.translatable("itemGroup.pokopia"))
            .icon { ItemStack(HABITAT_SCANNER.get()) }
            .displayItems { _, output ->
                output.accept(HABITAT_SCANNER.get())
            }
            .build()
    }

    fun register() {
        ITEMS.register()
        TABS.register()
    }
}
