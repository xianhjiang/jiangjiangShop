package com.spzx.order.controller;

import com.github.pagehelper.PageHelper;
import com.spzx.common.core.domain.R;
import com.spzx.common.core.utils.poi.ExcelUtil;
import com.spzx.common.core.web.controller.BaseController;
import com.spzx.common.core.web.domain.AjaxResult;
import com.spzx.common.core.web.page.TableDataInfo;
import com.spzx.common.log.annotation.Log;
import com.spzx.common.log.enums.BusinessType;
import com.spzx.common.security.annotation.InnerAuth;
import com.spzx.common.security.annotation.RequiresLogin;
import com.spzx.common.security.annotation.RequiresPermissions;
import com.spzx.order.api.domain.OrderInfo;
import com.spzx.order.domain.vo.OrderForm;
import com.spzx.order.domain.vo.TradeVo;
import com.spzx.order.service.IOrderInfoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "订单接口管理")
@RestController
@RequestMapping("/orderInfo")
public class OrderInfoController extends BaseController {
    @Autowired
    private IOrderInfoService orderInfoService;

    /**
     * 查询订单列表
     */
    @Operation(summary = "查询订单列表")
    @RequiresPermissions("user:orderInfo:list")
    @GetMapping("/list")
    public TableDataInfo list(OrderInfo orderInfo) {
        startPage();
        List<OrderInfo> list = orderInfoService.selectOrderInfoList(orderInfo);
        return getDataTable(list);
    }

    /**
     * 导出订单列表
     */
    @Operation(summary = "导出订单列表")
    @RequiresPermissions("user:orderInfo:export")
    @Log(title = "订单", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, OrderInfo orderInfo) {
        List<OrderInfo> list = orderInfoService.selectOrderInfoList(orderInfo);
        ExcelUtil<OrderInfo> util = new ExcelUtil<OrderInfo>(OrderInfo.class);
        util.exportExcel(response, list, "订单数据");
    }

    /**
     * 获取订单详细信息
     */
    @Operation(summary = "获取订单详细信息")
    @RequiresPermissions("user:orderInfo:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        return success(orderInfoService.selectOrderInfoById(id));
    }




    @Operation(summary = "订单结算")
    @RequiresLogin
    @GetMapping("/trade")
    public AjaxResult orderTradeData() {
        return success(orderInfoService.orderTradeData());
    }

    @Operation(summary = "下单")
    @RequiresLogin
    @PostMapping("/submitOrder")
    public AjaxResult submitOrder(@RequestBody OrderForm orderForm){
        Long orderId =  orderInfoService.submitOrder(orderForm);
        return success(orderId);
    }


    @Operation(summary = "获取订单信息")
    @RequiresLogin
    @GetMapping("getOrderInfo/{orderId}")
    public AjaxResult getOrderInfo(@PathVariable Long orderId) {
        OrderInfo orderInfo = orderInfoService.getById(orderId);
        return success(orderInfo);
    }


    @Operation(summary = "根据订单号获取订单信息")
    @InnerAuth
    @GetMapping("getByOrderNo/{orderNo}")
    public R<OrderInfo> getByOrderNo(@PathVariable String orderNo) {
        OrderInfo orderInfo = orderInfoService.getByOrderNo(orderNo);
        return R.ok(orderInfo);
    }

}