package com.spzx.product.service.impl;

import com.spzx.product.api.domain.Brand;
import com.spzx.product.mapper.BrandMapper;
import com.spzx.product.service.IBrandService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BrandServiceImpl implements IBrandService {

    @Autowired
    BrandMapper brandMapper;

    @Override
    public List<Brand> list(Brand brand) {
        return brandMapper.list(brand);
    }

    @Override
    public Brand getById(Long id) {
        return brandMapper.getById(id);
    }

    @Override
    public int save(Brand brand) {
        System.out.println("brand.getId() = " + brand.getId()); //保存前id属性值为null
        int rows = brandMapper.insert(brand);
        System.out.println("brand.getId() = " + brand.getId()); //保存后需要获取id属性值，需要配置主键回填配置
        return rows;
    }


    @Override
    public int update(Brand brand) {
        return brandMapper.update(brand);
    }

    @Override
    public int deleteBatch(Long[] ids) {
        return brandMapper.deleteBatch(ids);
    }

    @Override
    public List<Brand> selectBrandAll() {
        return brandMapper.selectBrandAll();
    }
}
