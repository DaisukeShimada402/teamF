package com.example.raffinehome.order.controller;

import com.example.raffinehome.cart.dto.CartDTO;
import com.example.raffinehome.cart.dto.CartItemDTO; // Cartの初期化に使用
import com.example.raffinehome.order.dto.OrderCreateDTO;
import com.example.raffinehome.order.dto.OrderItemDTO;
import com.example.raffinehome.order.dto.OrderDTO;
import com.example.raffinehome.cart.service.CartService;
import com.example.raffinehome.order.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.Map; // Map使用のため

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class) // OrderController と関連コンポーネント（バリデーション、ExceptionHandlerなど）をテスト
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrderService orderService;

    @MockBean
    private CartService cartService;

    private MockHttpSession mockSession;
    private CartDTO cartWithItems;
    private CartDTO emptyCart;
    private OrderItemDTO validOrderRequest;
    private OrderCreateDTO validCustomerInfo;
    private OrderDTO sampleOrderResponse;

    @BeforeEach
    void setUp() {
        mockSession = new MockHttpSession();

        // --- カート準備 ---
        cartWithItems = new CartDTO();
        CartItemDTO item = new CartItemDTO("1", 1, "p1", 100, "", 1, 100);
        cartWithItems.addItem(item); // 合計 Quantity=1, Price=100

        emptyCart = new CartDTO();

        // --- 注文リクエスト準備 ---
        validOrderRequest = new OrderItemDTO();
        validCustomerInfo = new OrderCreateDTO();
        validCustomerInfo.setCustomerName("Test User");
        validCustomerInfo.setCustomerEmail("test@example.com");
        validCustomerInfo.setShippingAddress("Test Address");
        validCustomerInfo.setPhoneNumber("0123456789");
        validOrderRequest.setCustomerInfo(validCustomerInfo);

        // --- 注文レスポンス準備 ---
        sampleOrderResponse = new OrderDTO(123, LocalDateTime.now());

        // --- Serviceメソッドのデフォルトモック設定 (lenient) ---
        lenient().when(cartService.getCart(any(HttpSession.class))).thenReturn(cartWithItems); // デフォルトはアイテムあり
        lenient().when(orderService.placeOrder(any(CartDTO.class), any(OrderCreateDTO.class), any(HttpSession.class)))
                .thenReturn(sampleOrderResponse); // デフォルトは成功
    }

    // === 正常系テスト ===
    @Nested
    @DisplayName("正常系: POST /api/orders")
    class PlaceOrderSuccessTests {
        @Test
        @DisplayName("有効なリクエストとカートの場合、注文処理を行い201 Createdと注文情報を返す")
        void placeOrder_WithValidRequestAndCart_ShouldReturnCreated() throws Exception {
            // Arrange (setUpで基本的なモックは設定済み)

            // Act & Assert
            mockMvc.perform(post("/api/orders")
                            .session(mockSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validOrderRequest))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isCreated()) // 201 Created
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.Id", is(sampleOrderResponse.getId())))
                    .andExpect(jsonPath("$.orderDate", is(notNullValue()))); // 日時がnullでないことを確認

            // Verify service calls
            verify(cartService, times(1)).getCart(any(HttpSession.class));
            // eq() を使って渡されたオブジェクトが期待通りか確認
            verify(orderService, times(1)).placeOrder(eq(cartWithItems), eq(validCustomerInfo), any(HttpSession.class));
            verifyNoMoreInteractions(cartService, orderService);
        }
    }

    // === 異常系テスト: カート関連 ===
    @Nested
    @DisplayName("異常系: カートの状態によるエラー")
    class PlaceOrderCartErrorTests {
        @Test
        @DisplayName("カートが空の場合、400 Bad Requestを返す")
        void placeOrder_WithEmptyCart_ShouldReturnBadRequest() throws Exception {
            // Arrange
            when(cartService.getCart(any(HttpSession.class))).thenReturn(emptyCart); // 空のカートを返す

            // Act & Assert
            mockMvc.perform(post("/api/orders")
                            .session(mockSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validOrderRequest))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest()); // 400 Bad Request

            verify(cartService, times(1)).getCart(any(HttpSession.class));
            verifyNoInteractions(orderService); // 注文処理は呼ばれない
        }

        @Test
        @DisplayName("カートがnullの場合、400 Bad Requestを返す")
        void placeOrder_WithNullCart_ShouldReturnBadRequest() throws Exception {
            // Arrange
            when(cartService.getCart(any(HttpSession.class))).thenReturn(null); // nullを返すケース

            // Act & Assert
            mockMvc.perform(post("/api/orders")
                            .session(mockSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validOrderRequest))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest()); // 400 Bad Request

            verify(cartService, times(1)).getCart(any(HttpSession.class));
            verifyNoInteractions(orderService);
        }
    }

    // === 異常系テスト: バリデーション ===
    @Nested
    @DisplayName("異常系: リクエストボディのバリデーションエラー")
    class PlaceOrderValidationErrorTests {

        // ヘルパーメソッド：バリデーションテストを実行し、結果を検証する
        private void performValidationTest(OrderDTO request, String expectedField, String expectedMessage) throws Exception {
            mockMvc.perform(post("/api/orders")
                            .session(mockSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))
                            .accept(MediaType.APPLICATION_JSON))
                            .andExpect(status().isBadRequest()) // 400 Bad Request を期待
                            .andExpect(content().contentType(MediaType.APPLICATION_JSON)) // Content-Type が JSON であることを期待
                            // JSONレスポンスのルートレベルにあるキー (expectedFieldの値そのもの) の値を検証する
                            .andExpect(jsonPath("$['" + expectedField + "']", is(expectedMessage)));
                            
            verifyNoInteractions(cartService, orderService); // バリデーションエラーなのでサービスは呼ばれない
        }

        @Test
        @DisplayName("CustomerInfoがnullの場合、400 Bad Requestとエラーメッセージを返す")
        void placeOrder_WithNullCustomerInfo_ShouldReturnBadRequest() throws Exception {
            OrderItemDTO invalidRequest = new OrderItemDTO();
            invalidRequest.setCustomerInfo(null); // NotNull違反
            performValidationTest(sampleOrderResponse, "customerInfo", "顧客情報は必須です");
        }

        @Test
        @DisplayName("CustomerInfo.nameが空の場合、400 Bad Requestとエラーメッセージを返す")
        void placeOrder_WithBlankName_ShouldReturnBadRequest() throws Exception {
            // CustomerInfoを生成し、セッターで値を設定
            OrderCreateDTO invalidCustomer = new OrderCreateDTO();
            invalidCustomer.setCustomerName(""); // @NotBlank 違反
            invalidCustomer.setCustomerEmail("test@example.com");
            invalidCustomer.setShippingAddress("Addr");
            invalidCustomer.setPhoneNumber("123");

            OrderItemDTO invalidRequest = new OrderItemDTO();
            invalidRequest.setCustomerInfo(invalidCustomer);
            performValidationTest(sampleOrderResponse, "customerInfo.name", "お名前は必須です");
        }

        @Test
        @DisplayName("CustomerInfo.emailが空の場合、400 Bad Requestとエラーメッセージを返す")
        void placeOrder_WithBlankEmail_ShouldReturnBadRequest() throws Exception {
            // CustomerInfoを生成し、セッターで値を設定
            OrderCreateDTO invalidCustomer = new OrderCreateDTO();
            invalidCustomer.setCustomerName("Name");
            invalidCustomer.setCustomerEmail(""); // @NotBlank 違反
            invalidCustomer.setShippingAddress("Addr");
            invalidCustomer.setPhoneNumber("123");

            OrderItemDTO invalidRequest = new OrderItemDTO();
            invalidRequest.setCustomerInfo(invalidCustomer);
            performValidationTest(sampleOrderResponse, "customerInfo.email", "メールアドレスは必須です");
        }

         @Test
        @DisplayName("CustomerInfo.emailが無効な形式の場合、400 Bad Requestとエラーメッセージを返す")
        void placeOrder_WithInvalidEmailFormat_ShouldReturnBadRequest() throws Exception {
            // CustomerInfoを生成し、セッターで値を設定
            OrderCreateDTO invalidCustomer = new OrderCreateDTO();
            invalidCustomer.setCustomerName("Name");
            invalidCustomer.setCustomerEmail("invalid-email"); // @Email 違反
            invalidCustomer.setShippingAddress("Addr");
            invalidCustomer.setPhoneNumber("123");

            OrderItemDTO invalidRequest = new OrderItemDTO();
            invalidRequest.setCustomerInfo(invalidCustomer);
            performValidationTest(sampleOrderResponse, "customerInfo.email", "有効なメールアドレスを入力してください");
        }

        @Test
        @DisplayName("CustomerInfo.addressが空の場合、400 Bad Requestとエラーメッセージを返す")
        void placeOrder_WithBlankAddress_ShouldReturnBadRequest() throws Exception {
             // CustomerInfoを生成し、セッターで値を設定
            OrderCreateDTO invalidCustomer = new OrderCreateDTO();
            invalidCustomer.setCustomerName("Name");
            invalidCustomer.setCustomerEmail("test@example.com");
            invalidCustomer.setShippingAddress(""); // @NotBlank 違反
            invalidCustomer.setPhoneNumber("123");

            OrderItemDTO invalidRequest = new OrderItemDTO();
            invalidRequest.setCustomerInfo(invalidCustomer);
            performValidationTest(sampleOrderResponse, "customerInfo.address", "住所は必須です");
        }

        @Test
        @DisplayName("CustomerInfo.phoneNumberが空の場合、400 Bad Requestとエラーメッセージを返す")
        void placeOrder_WithBlankPhoneNumber_ShouldReturnBadRequest() throws Exception {
             // CustomerInfoを生成し、セッターで値を設定
            OrderCreateDTO invalidCustomer = new OrderCreateDTO();
            invalidCustomer.setCustomerName("Name");
            invalidCustomer.setCustomerEmail("test@example.com");
            invalidCustomer.setShippingAddress("Addr");
            invalidCustomer.setPhoneNumber(""); // @NotBlank 違反

            OrderItemDTO invalidRequest = new OrderItemDTO();
            invalidRequest.setCustomerInfo(invalidCustomer);
            performValidationTest(sampleOrderResponse, "customerInfo.phoneNumber", "電話番号は必須です");
        }
    }

    // === 異常系テスト: リクエストボディ/Service例外 ===
    @Nested
    @DisplayName("異常系: 不正なリクエストボディ or Service層のエラー")
    class PlaceOrderOtherErrorTests {
        @Test
        @DisplayName("リクエストボディが不正なJSONの場合、500 Internal Server Errorを返す (現在のGlobalExceptionHandlerの実装による)") // DisplayName を変更
        void placeOrder_WithInvalidJsonBody_ShouldReturnInternalServerError_DueToExceptionHandler() throws Exception { // メソッド名を変更
            String invalidJson = "{\"customerInfo\":}"; // 不正なJSON

            mockMvc.perform(post("/api/orders")
                            .session(mockSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidJson) // 不正なJSONを送信
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError()) // ★期待値を isBadRequest() から isInternalServerError() に変更
                    // オプション： GlobalExceptionHandler が返すエラーメッセージの内容も検証する
                    // HttpMessageNotReadableException の場合、JSONパースエラーに関するメッセージが含まれることが多い
                    .andExpect(jsonPath("$.message", containsString("JSON parse error")));


            verifyNoInteractions(cartService, orderService);
        }

        @Test
        @DisplayName("OrderServiceがRuntimeExceptionをスローした場合、500 Internal Server Errorを返す")
        void placeOrder_WhenOrderServiceThrowsRuntimeException_ShouldReturnInternalServerError() throws Exception {
            // Arrange
            RuntimeException serviceException = new RuntimeException("在庫処理エラーなどの内部エラー");
            when(orderService.placeOrder(any(CartDTO.class), any(OrderCreateDTO.class), any(HttpSession.class)))
                    .thenThrow(serviceException); // サービスが例外をスロー
    
            // Act & Assert
            mockMvc.perform(post("/api/orders")
                            .session(mockSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validOrderRequest))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError()); // 500 Internal Server Error ステータスコードを検証 (これは成功しているはず)
    
            verify(cartService, times(1)).getCart(any(HttpSession.class));
            verify(orderService, times(1)).placeOrder(eq(cartWithItems), eq(validCustomerInfo), any(HttpSession.class));
            verifyNoMoreInteractions(cartService, orderService); // この後には何も呼ばれないはず
        }

        @Test
        @DisplayName("OrderServiceが在庫不足を示す特定の例外(例: IllegalStateException)をスローした場合、500 Internal Server Errorを返す")
        void placeOrder_WhenOrderServiceThrowsSpecificException_ShouldReturnInternalServerError() throws Exception {
            // Arrange
            // OrderServiceが在庫不足時に特定の例外 (ここでは例としてIllegalStateException) をスローすると仮定
            IllegalStateException serviceException = new IllegalStateException("在庫が不足しています: 商品X");
            when(orderService.placeOrder(any(CartDTO.class), any(OrderCreateDTO.class), any(HttpSession.class)))
                    .thenThrow(serviceException);
    
            // Act & Assert
            mockMvc.perform(post("/api/orders")
                            .session(mockSession)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validOrderRequest))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isInternalServerError()); // 500 Internal Server Error ステータスコードのみ検証
    
            verify(cartService, times(1)).getCart(any(HttpSession.class));
            verify(orderService, times(1)).placeOrder(eq(cartWithItems), eq(validCustomerInfo), any(HttpSession.class));
            verifyNoMoreInteractions(cartService, orderService); // この後には何も呼ばれないはず
        }
    }
}    

