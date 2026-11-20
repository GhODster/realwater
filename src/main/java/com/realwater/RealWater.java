package com.realwater;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RealWater implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("realwater");
    @Override
    public void onInitialize() { LOGGER.info("Real Water cargado"); }
}
