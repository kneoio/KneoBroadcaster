package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.aihelper.AnimationDTO;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Random;

@ApplicationScoped
public class AnimationService {

    private static final String[] ANIMATION_TYPES = {
            "static", "static", "static", "static", "static", "gradient", "glow"
    };

    private static final double RANDOM_ANIMATION_PROBABILITY = 0.3;
    private static final double STATIC_WEIGHT_IN_TYPES = 0.8; // 80% chance for static when enabled

    private static final double MIN_SPEED = 0.1;
    private static final double MAX_SPEED = 10.0;
    private static final double STATIC_SPEED = 1.0;
    private static final double GRADIENT_MIN_SPEED = 0.8;
    private static final double GRADIENT_MAX_SPEED = 2.0;
    private static final double GLOW_MIN_SPEED = 0.6;
    private static final double GLOW_MAX_SPEED = 2.0;

    private final Random random = new Random();

    public AnimationDTO generateRandomAnimation() {
        boolean enabled = random.nextDouble() < RANDOM_ANIMATION_PROBABILITY;

        if (!enabled) {
            return new AnimationDTO(false, "static", STATIC_SPEED);
        }

        String type = getRandomAnimationType();
        double speed = generateSpeedForType(type);

        return new AnimationDTO(true, type, speed);
    }

    private String getRandomAnimationType() {
        if (random.nextDouble() < STATIC_WEIGHT_IN_TYPES) {
            return "static";
        }

        return random.nextBoolean() ? "gradient" : "glow";
    }

    private double generateSpeedForType(String type) {
        return switch (type) {
            case "static" -> STATIC_SPEED;
            case "gradient" -> GRADIENT_MIN_SPEED + (random.nextDouble() * (GRADIENT_MAX_SPEED - GRADIENT_MIN_SPEED));
            case "glow" -> GLOW_MIN_SPEED + (random.nextDouble() * (GLOW_MAX_SPEED - GLOW_MIN_SPEED));
            default -> STATIC_SPEED + (random.nextDouble());
        };
    }
}