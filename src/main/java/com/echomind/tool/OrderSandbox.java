package com.echomind.tool;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic in-memory order fixtures for the address-change sandbox.
 * It never contacts or mutates a real order system; mutations exist only inside this process.
 */
public final class OrderSandbox {

    public record Address(String province, String city, String district, String street, String detail) {
        public Address {
            province = clean(province);
            city = clean(city);
            district = clean(district);
            street = clean(street);
            detail = clean(detail);
        }

        public String full() {
            return province + city + district + street + detail;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("province", province);
            value.put("city", city);
            value.put("district", district);
            value.put("street", street);
            value.put("detail", detail);
            value.put("full", full());
            return value;
        }
    }

    public static final class Order {
        private final String orderId;
        private final String userId;
        private final String status;
        private volatile String recipient;
        private volatile String phone;
        private volatile Address address;

        private Order(String orderId, String userId, String status, String recipient, String phone, Address address) {
            this.orderId = orderId;
            this.userId = userId;
            this.status = status;
            this.recipient = recipient;
            this.phone = phone;
            this.address = address;
        }

        public String orderId() { return orderId; }
        public String userId() { return userId; }
        public String status() { return status; }
        public boolean shipped() { return status.equals("shipped") || status.equals("delivered"); }
        public String recipient() { return recipient; }
        public String phone() { return phone; }
        public Address address() { return address; }

        /** Rechecks shipping state under the same lock as the mutation. */
        synchronized boolean update(String recipient, String phone, Address address) {
            if (shipped()) {
                return false;
            }
            this.recipient = clean(recipient);
            this.phone = clean(phone);
            this.address = address;
            return true;
        }
    }

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    public static OrderSandbox demo() {
        OrderSandbox sandbox = new OrderSandbox();
        sandbox.add(new Order("A1001", "eval_user", "processing", "李雷", "13800000000",
                new Address("北京市", "北京市", "海淀区", "中关村大街", "27号院1号楼101室")));
        sandbox.add(new Order("A1002", "eval_user", "shipped", "韩梅梅", "13900000000",
                new Address("上海市", "上海市", "浦东新区", "张江路", "88号")));
        sandbox.add(new Order("B2001", "demo-user", "processing", "王五", "13700000000",
                new Address("广东省", "深圳市", "南山区", "科技园南路", "15号")));
        sandbox.add(new Order("B2002", "demo-user", "shipped", "赵六", "13600000000",
                new Address("浙江省", "杭州市", "西湖区", "文三路", "100号")));
        sandbox.add(new Order("C3001", "other-user", "processing", "钱七", "13500000000",
                new Address("江苏省", "南京市", "鼓楼区", "中山路", "1号")));
        return sandbox;
    }

    private void add(Order order) {
        orders.put(order.orderId(), order);
    }

    /** Ownership is checked against the authenticated request user; unknown and foreign orders look identical. */
    public Optional<Order> owned(String orderId, String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        Order order = orders.get(normalize(orderId));
        return order != null && order.userId().equals(userId) ? Optional.of(order) : Optional.empty();
    }

    public static String normalize(String orderId) {
        return orderId == null ? "" : orderId.strip().replaceFirst("^#", "").toUpperCase(Locale.ROOT);
    }

    public static String maskPhone(String phone) {
        String value = clean(phone);
        if (value.length() < 7) {
            return "***";
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
