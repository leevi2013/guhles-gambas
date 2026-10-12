package de.mrgamba.oregamba;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Server-side click handler for the vanilla 9x3 chest screen.
 * All GUI slots are buttons; players cannot take the decorative items.
 */
public final class GambaScreenHandler extends ScreenHandler {
    private final Inventory inventory;

    public GambaScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory) {
        super(ScreenHandlerType.GENERIC_9X3, syncId);
        this.inventory = inventory;
        inventory.onOpen(playerInventory.player);

        // Three rows of the chest inventory.
        for (int slot = 0; slot < 27; slot++) {
            int x = 8 + (slot % 9) * 18;
            int y = 18 + (slot / 9) * 18;
            addSlot(new Slot(inventory, slot, x, y) {
                @Override
                public boolean canTakeItems(PlayerEntity player) {
                    return false;
                }

                @Override
                public boolean canInsert(ItemStack stack) {
                    return false;
                }
            });
        }

        // Player inventory positions matching the standard 9x3 chest layout.
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, 9 + row * 9 + column,
                        8 + column * 18, 85 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column, 8 + column * 18, 143));
        }
    }

    public Inventory getGambaInventory() {
        return inventory;
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        // Never let the client move items in or out of the fake chest inventory.
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }
        if (slotIndex < 0 || slotIndex >= 27 || actionType != SlotActionType.PICKUP) {
            return;
        }
        GambaMod.handleGuiClick(serverPlayer, this, slotIndex);
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        inventory.onClose(player);
        if (player instanceof ServerPlayerEntity serverPlayer) {
            GambaMod.onMenuClosed(serverPlayer, this);
        }
    }
}
