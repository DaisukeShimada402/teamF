package com.example.raffinehome.order.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OrederCreate {
    @NotBlank(message = "お名前は必須です")
    private String name;
    
    @NotBlank(message = "メールアドレスは必須です")
    @Email(message = "有効なメールアドレスを入力してください")
    private String email;
    
    @NotBlank(message = "住所は必須です")
    private String address;
    
    @NotBlank(message = "電話番号は必須です")
    private String phoneNumber;
}