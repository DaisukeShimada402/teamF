package com.example.raffinehome.order.service;

import com.example.raffinehome.cart.dto.CartDTO;
import com.example.raffinehome.cart.dto.CartItemDTO;
import com.example.raffinehome.order.dto.OrderDTO;
import com.example.raffinehome.order.dto.OrderItemDTO;
import com.example.raffinehome.order.dto.OrderCreateDTO;
import com.example.raffinehome.order.entity.Order;
import com.example.raffinehome.order.entity.OrderItem;
import com.example.raffinehome.product.entity.Product;
import com.example.raffinehome.order.repository.OrderItemRepository; // 不要だがモックは用意
import com.example.raffinehome.order.repository.OrderRepository;
import com.example.raffinehome.product.repository.ProductRepository;
import com.example.raffinehome.cart.service.CartService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.jaxb.SpringDataJaxb.OrderDto;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.annotation.Transactional; // @Transactional テスト用

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderDetailRepository; // 直接は使わない想定だが、依存性として存在
    @Mock
    private ProductRepository productRepository;
    @Mock
    private CartService cartService;

    @InjectMocks
    private OrderService orderService;

    private HttpSession session;
    private CartDTO cart;
    private Product product1;
    private Product product2;
    private OrderItem orderRequest;
    private Order customerInfo;
    private OrderCreateDTO orderCreateDTO; // 注文リクエストのモック

    // OrderServiceTest.java の setUp メソッドを修正

    @BeforeEach
    void setUp() {
        session = new MockHttpSession();

        // 商品データ準備
        product1 = new Product();
        product1.setId(1);
        product1.setName("注文テスト商品1");
        product1.setPrice(1000);
        product1.setStockQuantity(10);

        product2 = new Product();
        product2.setId(2);
        product2.setName("注文テスト商品2");
        product2.setPrice(2000);
        product2.setStockQuantity(5);

        // カート準備
        cart = new CartDTO();
        CartItemDTO item1 = new CartItemDTO("1", 1, "注文テスト商品1", 1000, "/img1.png", 2, 2000);
        CartItemDTO item2 = new CartItemDTO("2", 2, "注文テスト商品2", 2000, "/img2.png", 1, 2000);
        // LinkedHashMap を使って追加順を保持する
        Map<String, CartItemDTO> items = new LinkedHashMap<>();
        items.put(item1.getId(), item1); // 商品1を先に追加
        items.put(item2.getId(), item2); // 商品2を次に追加
        cart.setItems(items);
        cart.calculateTotals(); // 合計 Quantity=3, Price=4000

        // 注文リクエスト準備
        orderRequest = new OrderItem();
        customerInfo = new Order();
        customerInfo.setCustomerName("山田 太郎");
        customerInfo.setCustomerEmail("yamada@test.co.jp");
        customerInfo.setShippingAddress("東京都テスト区1-2-3");
        customerInfo.setPhoneNumber("03-1111-2222");
        orderRequest.setOrder(customerInfo);
        orderRequest.setProduct(product1); // 商品1を注文リクエストに設定
        orderCreateDTO = new OrderCreateDTO();
        orderCreateDTO.getCustomerInfo(); // 注文リクエストに顧客情報を設定
        orderCreateDTO.setCustomerName("山田 太郎");
        orderCreateDTO.setCustomerEmail("yamada@test.co.jp");
        orderCreateDTO.setShippingAddress("東京都テスト区1-2-3");
        orderCreateDTO.setPhoneNumber("03-1111-2222");
        

        // --- Mockito leninent 設定 ---
        lenient().when(productRepository.findById(1)).thenReturn(Optional.of(product1));
        lenient().when(productRepository.findById(2)).thenReturn(Optional.of(product2));
        lenient().when(productRepository.decreaseStock(anyInt(), anyInt())).thenReturn(1);
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order orderToSave = invocation.getArgument(0);
            if (orderToSave.getId() == null) {
                orderToSave.setId(123);
            }
            // Orderエンティティの addOrderDetail を使う場合、通常この関連設定は不要
            // orderToSave.getOrderDetails().forEach(detail ->
            // detail.setOrder(orderToSave));
            return orderToSave;
        });
        lenient().doNothing().when(cartService).clearCart(any(HttpSession.class));
    }

    // === 正常系テスト ===

    @Test
    @DisplayName("カートが有効で在庫も十分な場合、注文が作成され在庫が減りカートがクリアされる")
    void placeOrder_Success_ShouldCreateOrderDecreaseStockAndClearCart() {
        // Arrange (setUpで基本的なモックは設定済み)
        // 在庫減算の戻り値を明示的に設定 (デフォルトと同じだが、テストの意図を明確化)
        when(productRepository.decreaseStock(eq(1), eq(2))).thenReturn(1);
        when(productRepository.decreaseStock(eq(2), eq(1))).thenReturn(1);

        // Act
        OrderDTO response = orderService.placeOrder(cart, orderCreateDTO, session);

        // Assert: 結果の検証
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(123); // 保存時に設定したID
        assertThat(response.getOrderDate()).isNotNull(); // 注文日時が設定されている

        // Assert: 副作用の検証
        // 1. OrderRepository.save が正しい内容で1回呼ばれたか
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(1)).save(orderCaptor.capture());
        Order savedOrder = orderCaptor.getValue();

        // Orderエンティティの内容を詳細に検証
        assertThat(savedOrder.getCustomerName()).isEqualTo(customerInfo.getCustomerName());
        assertThat(savedOrder.getCustomerEmail()).isEqualTo(customerInfo.getCustomerEmail());
        assertThat(savedOrder.getShippingAddress()).isEqualTo(customerInfo.getShippingAddress());
        assertThat(savedOrder.getPhoneNumber()).isEqualTo(customerInfo.getPhoneNumber());
        assertThat(savedOrder.getTotalAmount()).isEqualTo(cart.getTotalPrice()); // 合計金額
        assertThat(savedOrder.getOrderStatus()).isEqualTo("PENDING"); // ステータス
        assertThat(savedOrder.getOrderDate()).isNotNull(); // 注文日時 (厳密な比較は難しいのでNotNull)

        // OrderDetailエンティティの内容を検証
        assertThat(savedOrder.getOrderDetails()).hasSize(2);
        // 商品1のOrderDetail
        Optional<OrderItem> detail1Opt = savedOrder.getOrderDetails().stream()
                .filter(d -> d.getProduct() != null && d.getProduct().getId() == 1).findFirst();
        assertThat(detail1Opt).isPresent();
        detail1Opt.ifPresent(detail -> {
            assertThat(detail.getProductName()).isEqualTo(product1.getName());
            assertThat(detail.getUnitPrice()).isEqualTo(product1.getPrice());
            assertThat(detail.getQuantity()).isEqualTo(2); // カートの数量
            assertThat(detail.getOrder()).isEqualTo(savedOrder); // Orderへの参照
        });
        // 商品2のOrderDetail
        Optional<OrderItem> detail2Opt = savedOrder.getOrderDetails().stream()
                .filter(d -> d.getProduct() != null && d.getProduct().getId() == 2).findFirst();
        assertThat(detail2Opt).isPresent();
        detail2Opt.ifPresent(detail -> {
            assertThat(detail.getProductName()).isEqualTo(product2.getName());
            assertThat(detail.getUnitPrice()).isEqualTo(product2.getPrice());
            assertThat(detail.getQuantity()).isEqualTo(1); // カートの数量
            assertThat(detail.getOrder()).isEqualTo(savedOrder); // Orderへの参照
        });

        // 2. ProductRepository.decreaseStock が正しい引数で呼ばれたか
        verify(productRepository, times(1)).decreaseStock(eq(1), eq(2)); // 商品1, 数量2
        verify(productRepository, times(1)).decreaseStock(eq(2), eq(1)); // 商品2, 数量1

        // 3. CartService.clearCart が呼ばれたか
        verify(cartService, times(1)).clearCart(session);

        // 4. 不要なメソッド呼び出しがないことの確認 (例)
        verify(orderDetailRepository, never()).save(any()); // OrderDetailRepositoryは直接使わない
    }

    @Test
    void placeOrder_Success_WithSingleItemInCart() {
    // 1商品だけのカートを個別に用意
    CartDTO singleItemCart = new CartDTO();
    CartItemDTO item1 = new CartItemDTO("1", 1, "注文テスト商品1", 1000, "/img1.png", 3, 3000);
    singleItemCart.addItem(item1);
    
    // ★このsingleItemCartを使う
    OrderDTO response = orderService.placeOrder(singleItemCart, orderCreateDTO, session);

    // 期待通り1件だけになる
    ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
    verify(orderRepository).save(orderCaptor.capture());
    Order savedOrder = orderCaptor.getValue();
    assertThat(savedOrder.getOrderDetails()).hasSize(1);
    // ...あとはassert
}

    // === 異常系テスト ===

    @Test
    @DisplayName("カートがnullの場合、nullを返し何も処理しない")
    void placeOrder_Fail_WhenCartIsNull_ShouldReturnNull() {
        // Arrange
        CartDTO nullCart = null;

        // Act
        OrderDTO response = orderService.placeOrder(nullCart, orderCreateDTO, session);

        // Assert
        assertThat(response).isNull();
        verify(orderRepository, never()).save(any());
        verify(productRepository, never()).findById(anyInt()); // findByIdも呼ばれない
        verify(productRepository, never()).decreaseStock(anyInt(), anyInt());
        verify(cartService, never()).clearCart(any());
    }

    @Test
    @DisplayName("カートが空の場合、nullを返し何も処理しない")
    void placeOrder_Fail_WhenCartIsEmpty_ShouldReturnNull() {
        // Arrange
        CartDTO emptyCart = new CartDTO();

        // Act
        OrderDTO response = orderService.placeOrder(emptyCart, orderCreateDTO, session);

        // Assert
        assertThat(response).isNull();
        verify(orderRepository, never()).save(any());
        verify(productRepository, never()).findById(anyInt()); // findByIdも呼ばれない
        verify(productRepository, never()).decreaseStock(anyInt(), anyInt());
        verify(cartService, never()).clearCart(any());
    }

    @Test
    void placeOrder_Fail_WhenOrderRequestIsNull_ShouldThrowNPE() {
    // 正しいカート（空じゃない）とnullのorderCreateDTOを渡す
    CartDTO cart = new CartDTO();
    // 商品を追加してカートを非空に
    CartItemDTO item = new CartItemDTO("1", 1, "テスト", 100, "/a.png", 1, 100);
    cart.addItem(item); // addItemメソッドはあなたの実装次第

    assertThatThrownBy(() -> orderService.placeOrder(cart, null, session))
        .isInstanceOf(NullPointerException.class);
}

    @Test
    @DisplayName("在庫確認中に商品が見つからない場合、RuntimeExceptionが発生しロールバックされる")
    void placeOrder_Fail_WhenProductNotFoundDuringStockCheck_ShouldThrowException() {
        // Arrange: 商品2が見つからないケース
        when(productRepository.findById(2)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> orderService.placeOrder(cart, orderCreateDTO, session))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("在庫不足または商品未存在: " + "注文テスト商品2"); // サービス内のメッセージを確認

        // 副作用がないこと（ロールバックされること）を確認
        verify(orderRepository, never()).save(any()); // Orderは保存されない
        verify(productRepository, never()).decreaseStock(anyInt(), anyInt()); // 在庫減算も実行されない
        verify(cartService, never()).clearCart(any()); // カートクリアも実行されない
        verify(productRepository, times(1)).findById(1); // 商品1のチェックは行われる
        verify(productRepository, times(1)).findById(2); // 商品2のチェックで失敗
    }

    @Test
    @DisplayName("複数の商品のうち、一部の商品で在庫不足の場合、RuntimeExceptionが発生しロールバックされる")
    void placeOrder_Fail_WhenPartialStockIsInsufficient_ShouldThrowException() {
        // Arrange: 商品1の在庫が不足しているケース (カートには2つ要求)
        product1.setStockQuantity(1); // 在庫を1に設定 (要求は2)
        when(productRepository.findById(1)).thenReturn(Optional.of(product1)); // 更新されたproduct1を返すように再設定

        // Act & Assert
        assertThatThrownBy(() -> orderService.placeOrder(cart, orderCreateDTO, session))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("在庫不足または商品未存在: " + product1.getName()); // 例外メッセージを確認

        // 副作用がないこと（ロールバックされること）を確認
        verify(orderRepository, never()).save(any());
        verify(productRepository, never()).decreaseStock(anyInt(), anyInt());
        verify(cartService, never()).clearCart(any());
        // 検証: findById(1) は呼ばれるが、findById(2) は呼ばれないことを確認
        verify(productRepository, times(1)).findById(1); // 商品1のチェックは行われる
        verify(productRepository, never()).findById(2); // 商品2のチェックには到達しない ★修正点
    }

    @Test
    @DisplayName("在庫減算(decreaseStock)が失敗(0を返す)した場合、IllegalStateExceptionが発生しロールバックされる")
    void placeOrder_Fail_WhenDecreaseStockFails_ShouldThrowIllegalStateException() {
        // Arrange
        // decreaseStock で商品2の更新が失敗するケースをシミュレート
        when(productRepository.decreaseStock(eq(1), eq(2))).thenReturn(1); // 商品1は成功
        when(productRepository.decreaseStock(eq(2), eq(1))).thenReturn(0); // 商品2は失敗(0行更新)

        // Act & Assert
        // IllegalStateException がスローされることを検証
        assertThatThrownBy(() -> orderService.placeOrder(cart, orderCreateDTO, session))
                .isInstanceOf(IllegalStateException.class)
                // ★ スローされる例外メッセージに期待する内容が含まれているか検証
                .hasMessageContaining("在庫の更新に失敗しました")
                .hasMessageContaining("商品ID: 2") // 失敗した商品ID
                .hasMessageContaining("更新行数: 0"); // 失敗時の更新行数

        // 副作用がないこと（ロールバックされるため、一部の処理は実行されるが最終的な状態変更はない）
        // save や clearCart は呼ばれないことを確認
        verify(orderRepository, never()).save(any(Order.class));
        verify(cartService, never()).clearCart(session);
        // decreaseStock は呼ばれるが、失敗した時点で例外が発生する
        verify(productRepository, times(1)).decreaseStock(eq(1), eq(2)); // 成功する分は呼ばれる
        verify(productRepository, times(1)).decreaseStock(eq(2), eq(1)); // 失敗する分も呼ばれる
    }

    @Test
    @DisplayName("注文保存(orderRepository.save)で例外が発生した場合、RuntimeExceptionがスローされロールバックされる")
    // @Transactional // テスト自体にトランザクションをかける場合 (通常は不要)
    void placeOrder_Fail_WhenOrderSaveThrowsException_ShouldRollback() {
        // Arrange
        RuntimeException dbException = new RuntimeException("DB保存エラー");
        // orderRepository.save が呼ばれたら例外をスロー
        when(orderRepository.save(any(Order.class))).thenThrow(dbException);

        // 在庫減算は成功するようにしておく
        when(productRepository.decreaseStock(eq(1), eq(2))).thenReturn(1);
        when(productRepository.decreaseStock(eq(2), eq(1))).thenReturn(1);

        // Act & Assert
        // placeOrder内で発生した例外がそのままスローされることを確認
        assertThatThrownBy(() -> orderService.placeOrder(cart, orderCreateDTO, session))
                .isInstanceOf(RuntimeException.class)
                .isEqualTo(dbException); // スローされた例外がモックしたものと同じか確認

        // 副作用がないこと（ロールバックされること）を確認
        verify(orderRepository, times(1)).save(any(Order.class)); // saveは試行される
        // 在庫減算メソッドはsaveの前に呼ばれるため実行されるが、ロールバックされるはず
        verify(productRepository, times(1)).decreaseStock(eq(1), eq(2));
        verify(productRepository, times(1)).decreaseStock(eq(2), eq(1));
        // clearCartはsaveの後なので呼ばれない
        verify(cartService, never()).clearCart(session);

        // 注意: 実際のDBロールバックを Mockito だけで完全に検証するのは難しい。
        // @DataJpaTest などを使った統合テストレベルでの検証がより確実。
        // ここでは、例外発生後の不要な処理(clearCart)が呼ばれないことを確認する。
    }

    @Test
    @DisplayName("カートクリア(cartService.clearCart)で例外が発生した場合、RuntimeExceptionがスローされロールバックされる")
    void placeOrder_Fail_WhenClearCartThrowsException_ShouldRollback() {
        // Arrange
        RuntimeException clearCartException = new RuntimeException("カートクリアエラー");
        // cartService.clearCart が呼ばれたら例外をスロー
        doThrow(clearCartException).when(cartService).clearCart(any(HttpSession.class));

        // orderRepository.save は成功するようにしておく
        // (setUpのlenient設定でカバーされる)

        // Act & Assert
        // placeOrder内で発生した例外がそのままスローされることを確認
        assertThatThrownBy(() -> orderService.placeOrder(cart, orderCreateDTO, session))
                .isInstanceOf(RuntimeException.class)
                .isEqualTo(clearCartException);

        // 副作用がないこと（ロールバックされること）を確認
        verify(orderRepository, times(1)).save(any(Order.class)); // saveは呼ばれる
        verify(productRepository, times(1)).decreaseStock(eq(1), eq(2)); // decreaseStockも呼ばれる
        verify(cartService, times(1)).clearCart(session); // clearCartも試行される

        // 注意: 上記と同様、DBロールバックの完全な検証は難しいが、
        // 例外が適切に伝播することを確認する。
    }

}
