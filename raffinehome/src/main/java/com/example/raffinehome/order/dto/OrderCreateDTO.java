package com.example.raffinehome.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateDTO {
    private Integer orderId;
    private LocalDateTime orderDate;
}