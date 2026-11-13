# 屏蔽门 (玻璃)

屏蔽门实际上是第二个制作的物件, 当时并没有考虑到将门和玻璃分开, 因此需要把门和玻璃放到一起.<br>
与其他物件不同, 一个屏蔽门玻璃是由左右两部分拼接而成的, 这意味着更大的用户自由度.<br>
与此同时, 玻璃支持使用自定义脚本, 也有内置的 `doorlight` 脚本供创建门灯.

- 物件类别: `screendoor`

## 配置文件

屏蔽门的配置文件基本格式如下:

    {
    "model": "fangsu:screendoor/bj/bj_screendoor_1.obj",
    "door": {
        ... (见门那一部分)
    },
    "glass": {
        "left": [
            {
                "id": "door",
                "text": "fangsu.item.bjscdoor_bg1.glass.left.door.text",
                "contentText": "fangsu.item.bjscdoor_bg1.glass.left.door.contentText",
                "subModel": "g_ld",
                "shape": [
                    [
                        8,
                        0,
                        11,
                        16,
                        26,
                        13
                    ]
                ],
                "script": [
                    {
                        "scriptFile": "fangsu:screendoor/doorlight.js",
                        "scriptText": "var doorSide=false;var doorLightModel=\"fangsu:screendoor/bj/bj_sc_1_doorlight.obj\";var doorLightSubModel=\"l\";"
                    }
                ]
            },
            ...
        ],
        "right": [
            ...
        ]
    }

}

在上面的例子中:

- `glass` 用于区分门和玻璃.<br>
- `left` 和 `right` 一个玻璃方块是由左右两部分拼接而成的, 用于区分左右.<br>

对于每个玻璃:

- `id` 这个物件的唯一 id, 不能重复或为"";
- `text` 这个物件在配置页面的显示名称, 可使用文本组件;
- `contentText` 可选, 在配置页面右侧显示的文本;
- `model` 屏蔽门模型;
- `flipV` 是否翻转 UV 的 Y 轴 (如果你使用 Blockbench 或 Blender 建模, 就写 true, 如果你用水杉, 就写 false) ;
- `subModel` 子模型的名称;
- `shape` 碰撞箱的位置;
- `script` 可选 这个玻璃会运行什么脚本.
    - `scriptFile` 脚本位置;
    - `scriptText` 在脚本运行开始前需要运行什么代码;

    - 对于 `doorlight` , 应当如下设置:
        - 单独建模一个打开的门灯;
        - 在 `scriptText`中填写 (需要转义字符)

                "var doorSide=false;
                var doorLightModel=\"门灯位置\";
                var doorLightSubModel=\"l\"

## 建模

模型应当为包括多个组的模型, 注意导出时勾选三角化网格.

> 在 OBJ 文件中, `g` 或 `o` 指令设定分组 (部件). 大多建模软件导出 OBJ 时可选择一并导出分组信息. 当文件里同时有 `g` 和 `o` 指令时只会按照先出现的一种进行分组.

**特别注意**: 对于屏蔽门, 你需要将玻璃和门放到同一个 obj 文件中.
