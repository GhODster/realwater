package com.realwater;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class RealWater implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("realwater");

    /** Particle amounts of the water buckets. */
    public static final int[] BUCKET_AMOUNTS = {1000, 3000, 4500, 8000, 15000, 25000};

    @FunctionalInterface
    public interface PourHandler { void pour(double x, double y, double z, int amount); }

    /** Set by the client at startup (keeps client classes out of the server). */
    public static volatile PourHandler pourHandler = null;

    @Override
    public void onInitialize() {
        List<Item> buckets = new ArrayList<>();
        for (int amount : BUCKET_AMOUNTS) {
            Item item = Registry.register(Registries.ITEM,
                    Identifier.of("realwater", "water_bucket_" + amount),
                    new ParticleBucketItem(new Item.Settings().maxCount(1), amount));
            buckets.add(item);
        }
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            for (Item item : buckets) entries.add(item);
        });
        LOGGER.info("Real Water loaded");
    }
}
