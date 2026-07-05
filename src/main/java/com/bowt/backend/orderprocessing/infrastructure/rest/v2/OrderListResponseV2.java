package com.bowt.backend.orderprocessing.infrastructure.rest.v2;

import java.util.List;

public record OrderListResponseV2(List<OrderResponseV2> content, PageMetadata page) {
}