package io.kneo.broadcaster.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Setter
@Getter
@NoArgsConstructor
public class UserData {
    private Map<String, String> data = new HashMap<>();

    public UserData(Map<String, String> data) {
        this.data = data != null ? new HashMap<>(data) : new HashMap<>();
    }

    public void put(String key, String value) {
        if (key != null && value != null) {
            data.put(key, value);
        }
    }

    public String get(String key) {
        return data.get(key);
    }

    public void remove(String key) {
        data.remove(key);
    }

    public boolean containsKey(String key) {
        return data.containsKey(key);
    }

    public Map<String, String> getData() {
        return new HashMap<>(data);
    }
}
