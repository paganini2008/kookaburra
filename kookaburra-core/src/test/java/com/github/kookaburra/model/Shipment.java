package com.github.kookaburra.model;

import com.github.kookaburra.Ref;
import com.github.kookaburra.annotation.Entity;
import com.github.kookaburra.annotation.Id;
import com.github.kookaburra.annotation.Index;
import com.github.kookaburra.annotation.Version;

import java.util.List;
import java.util.Map;

/**
 * Nested-object test entity: three embedded levels, a list of embedded objects (themselves nesting {@link Money}),
 * a map of embedded values and references to other shipments (including cycles).
 *
 * @Author: Fred Feng
 * @Date: 15/10/2026
 * @Version 1.0.0
 */
@Entity
public class Shipment {
    @Id long oid;
    @Version long version;
    @Index String code;
    Route route;
    List<Item> items;
    Map<String, Place> stops;
    Ref<Shipment> parent;
    @Index List<String> labels;

    public Shipment() {
    }

    public Shipment(String code, Route route) {
        this.code = code;
        this.route = route;
    }

    public long getOid() { return oid; }
    public long getVersion() { return version; }
    public String getCode() { return code; }
    public Route getRoute() { return route; }
    public List<Item> getItems() { return items; }
    public Map<String, Place> getStops() { return stops; }
    public Ref<Shipment> getParent() { return parent; }
    public List<String> getLabels() { return labels; }

    public Shipment route(Route v) { route = v; return this; }
    public Shipment items(List<Item> v) { items = v; return this; }
    public Shipment stops(Map<String, Place> v) { stops = v; return this; }
    public Shipment parent(Ref<Shipment> v) { parent = v; return this; }
    public Shipment labels(List<String> v) { labels = v; return this; }
}
