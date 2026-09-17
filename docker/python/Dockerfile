# python 沙箱镜像:SQL 数据的"计算车间"(仅计算/出图,运行期断网)
# 构建:docker build -t databuddy-python:3.12 docker/python
FROM python:3.12-slim

# 计算与出图三件套(锁定大版本,保证结果确定性;运行期无网络,不会动态装库)
# pip 源可覆盖,国内加速:
#   docker build --build-arg PIP_INDEX=https://pypi.tuna.tsinghua.edu.cn/simple -t databuddy-python:3.12 docker/python
ARG PIP_INDEX=https://pypi.org/simple
RUN pip install --no-cache-dir -i ${PIP_INDEX} "numpy>=2,<3" "pandas>=2,<3" "matplotlib>=3.9,<4"

# 中文字体(matplotlib 中文标签必需,否则全是方块)
# apt 源可覆盖(与 PIP_INDEX 同为加速开关):--build-arg APT_MIRROR=mirrors.tuna.tsinghua.edu.cn
ARG APT_MIRROR=deb.debian.org
RUN sed -i "s|deb.debian.org|${APT_MIRROR}|g" /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

# matplotlib 全局配置:默认中文字体 + 正常显示负号(改原行,避免重复键警告;自检防静默失效)
RUN sed -i -e 's|^#font.sans-serif:.*|font.sans-serif: Noto Sans CJK SC, DejaVu Sans|' \
              -e 's|^#axes.unicode_minus:.*|axes.unicode_minus: False|' \
              /usr/local/lib/python3.12/site-packages/matplotlib/mpl-data/matplotlibrc \
    && grep -q 'font.sans-serif: Noto Sans CJK SC' /usr/local/lib/python3.12/site-packages/matplotlib/mpl-data/matplotlibrc

# 非 root 运行(与运行时 --user 1000 一致);matplotlib 缓存写到 /tmp(根文件系统只读,tmpfs 可写)
RUN useradd -m -u 1000 sandbox
ENV HOME=/home/sandbox MPLCONFIGDIR=/tmp

# 常驻待命,等待 docker exec 派发执行
CMD ["sleep", "infinity"]
