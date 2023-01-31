package nl.rabobank.shadow_tool;

import java.util.List;

public class DummyObject {
    private final String name;
    private final String place;
    private final List<String> madrigals;

    public DummyObject(final String name, final String place, final List<String> madrigals) {
        this.name = name;
        this.place = place;
        this.madrigals = madrigals;
    }
}
