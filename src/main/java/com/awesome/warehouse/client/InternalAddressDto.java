package com.awesome.warehouse.client;

/** Shipping address inside {@link InternalOrderDto}. */
public record InternalAddressDto(String street, String city, String state, String zipCode, String country) {
}
