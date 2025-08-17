package io.kneo.broadcaster.service;

import io.kneo.broadcaster.dto.aihelper.AnimationDTO;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Random;

@ApplicationScoped
public class AnimationService {
    
    private static final String[] ANIMATION_TYPES = {
        "static", "gradient", "glow"
    };
    
    private static final double MIN_SPEED = 0.1;
    private static final double MAX_SPEED = 10.0;
    
    private final Random random = new Random();
    
    public AnimationDTO generateRandomAnimation() {
        boolean enabled = random.nextBoolean();
        
        if (!enabled) {
            return new AnimationDTO(false, "static", 1.0);
        }
        
        String type = getRandomAnimationType();
        double speed = generateSpeedForType(type);
        
        return new AnimationDTO(true, type, speed);
    }
    
    public AnimationDTO generateAnimationForMood(String mood) {
        if (mood == null || mood.trim().isEmpty()) {
            return generateRandomAnimation();
        }
        
        String lowerMood = mood.toLowerCase();
        boolean enabled = shouldEnableForMood(lowerMood);
        
        if (!enabled) {
            return new AnimationDTO(false, "static", 1.0);
        }
        
        String type = getAnimationTypeForMood(lowerMood);
        double speed = getSpeedForMood(lowerMood);
        
        return new AnimationDTO(true, type, speed);
    }
    
    private boolean shouldEnableForMood(String mood) {
        if (mood.contains("calm") || mood.contains("peaceful") || mood.contains("relaxed")) {
            return random.nextDouble() < 0.3;
        }
        if (mood.contains("energetic") || mood.contains("happy") || mood.contains("aggressive")) {
            return random.nextDouble() < 0.8;
        }
        return random.nextDouble() < 0.6;
    }
    
    private String getAnimationTypeForMood(String mood) {
        if (mood.contains("romantic") || mood.contains("feel-good") || mood.contains("love")) {
            return random.nextBoolean() ? "gradient" : "glow";
        }
        if (mood.contains("energetic") || mood.contains("happy") || mood.contains("aggressive") || mood.contains("upbeat")) {
            return "glow";
        }
        if (mood.contains("calm") || mood.contains("dreamy") || mood.contains("ethereal") || mood.contains("peaceful")) {
            return "gradient";
        }
        if (mood.contains("mysterious") || mood.contains("dramatic") || mood.contains("suspense")) {
            return "glow";
        }
        if (mood.contains("dark") || mood.contains("epic") || mood.contains("intense") || mood.contains("powerful")) {
            return "glow";
        }
        
        return getRandomAnimationType();
    }
    
    private double getSpeedForMood(String mood) {
        if (mood.contains("calm") || mood.contains("peaceful") || mood.contains("relaxed") || mood.contains("slow")) {
            return 0.5 + (random.nextDouble() * 0.3);
        }
        if (mood.contains("energetic") || mood.contains("fast") || mood.contains("aggressive") || mood.contains("intense")) {
            return 2.0 + (random.nextDouble() * 1.0);
        }
        if (mood.contains("dramatic") || mood.contains("epic")) {
            return 1.5 + (random.nextDouble() * 1.0);
        }
        
        return 1.0 + (random.nextDouble() * 0.5);
    }
    
    private String getRandomAnimationType() {
        return ANIMATION_TYPES[random.nextInt(ANIMATION_TYPES.length)];
    }
    
    private double generateSpeedForType(String type) {
        switch (type) {
            case "static":
                return 1.0;
            case "gradient":
                return 0.8 + (random.nextDouble() * 1.2);
            case "glow":
                return 0.6 + (random.nextDouble() * 1.4);
            default:
                return 1.0 + (random.nextDouble() * 1.0);
        }
    }
}
