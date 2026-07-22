package com.spzx.product.service;


import com.spzx.product.api.domain.Brand;

import java.util.List;

/**
 * 品牌管理
 */
public interface IBrandService {

    /**
     * 查询品牌列表
     * @param brand
     * @return
     */
    List<Brand> list(Brand brand);


    /**
     * 查询品牌详情
     * @param id 品牌主键
     * @return 品牌对象
     */
    Brand getById(Long id);

    /**
     * 添加品牌
     * @param brand 表单数据
     * @return 添加结果：1表示成功   0表示失败
     */
    int save(Brand brand);

    /**
     * 修改品牌
     * @param brand 表单数据
     * @return 修改结果：1表示成功   0表示失败
     */
    int update(Brand brand);

    /**
     * 删除品牌
     * @param ids 一个或多个id
     * @return 结果：大于等于1表示成功   0表示失败
     */
    int deleteBatch(Long[] ids);

    /**
     * 查询全部品牌
     * @return 品牌集合
     */
    List<Brand> selectBrandAll();
}
