package io.github.rabobank.shadow_tool;

import java.util.List;

public record DummyObject(String name, String place, List<String> madrigals) {

    public DummyObject(final String name, final String place, final List<String> madrigals) {
        this.name = name;
        this.place = place;
        this.madrigals = List.copyOf(madrigals);
    }
}
