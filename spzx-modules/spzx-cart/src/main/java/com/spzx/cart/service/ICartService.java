package com.spzx.cart.service;

import com.spzx.cart.api.domain.CartInfo;

import java.util.List;

public interface ICartService {

    /**
     * 添加购物车
     * @param skuId 商品skuId
     * @param skuNum 商品购买数量，默认1     增量值。
     */
    void addToCart(Long skuId, Integer skuNum);

    /**
     * 查看购物车列表
     * @return 购物车商品信息
     */
    List<CartInfo> getCartList();

    /**
     * 删除购物车
     * @param skuId
     */
    void deleteCart(Long skuId);

    /**
     * 更新选中状态
     * @param skuId
     * @param isChecked   1选中   0未选中
     */
    void checkCart(Long skuId, Integer isChecked);

    /**
     * 更新购物车商品全部选中状态
     * @param isChecked  1 全选   0全不选
     */
    void allCheckCart(Integer isChecked);

    /**
     * 清空购物车
     */
    void clearCart();

    /**
     * 查询用户购物车列表中选中商品列表
     * @param userId 当前用户id
     * @return 购物车列表中选中商品列表
     */
    List<CartInfo> getCartCheckedList(Long userId);

    /**
     * 更新购物车价格
     * @param userId
     * @return
     */
    Boolean updateCartPrice(Long userId);

    /**
     * 删除购物车中打钩商品
     * @param userId
     * @return
     */
    Boolean deleteCartCheckedList(Long userId);
}
