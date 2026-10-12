package com.wcmt.item;

import appeng.items.materials.UpgradeCardItem;

/**
 * 元件工作卡。继承 AE2 自己的升级卡即可获得标准提示文本，列出接受它的机器
 * （“Supported By: ...”），这正是它的用途。
 */
public class ComponentWorkCardItem extends UpgradeCardItem {

    public ComponentWorkCardItem(Properties properties) {
        super(properties);
    }
}
