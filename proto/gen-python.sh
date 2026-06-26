#!/usr/bin/env bash
# 重新生成 user / chat / vision 三个 proto 的 Python gRPC stub。
#
# 依赖:grpcio-tools==1.68.1(与 proto/pom.xml 的 grpc.version 对齐)。
# 没装的话临时装一个 venv 即可:
#   python3 -m venv /tmp/protogen && /tmp/protogen/bin/pip install grpcio-tools==1.68.1
#   PYBIN=/tmp/protogen/bin/python ./gen-python.sh
#
# 生成物已入库(proto/<svc>/python/dating_proto_youjianxin_<svc>/),改了 .proto 后重跑本脚本并升版本号再发 Nexus。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PYBIN="${PYBIN:-python3}"

gen() {
  local svc="$1"
  local pkg="dating_proto_youjianxin_${svc}"
  local proto_dir="$HERE/${svc}"
  local out="$proto_dir/python/$pkg"
  rm -rf "$out"; mkdir -p "$out"
  "$PYBIN" -m grpc_tools.protoc -I "$proto_dir" \
    --python_out="$out" --grpc_python_out="$out" "${svc}.proto"
  # grpc stub 默认 `import <svc>_pb2`,包内要改成相对导入
  sed -i'' -e "s/^import ${svc}_pb2 as/from . import ${svc}_pb2 as/" "$out/${svc}_pb2_grpc.py"
  : > "$out/__init__.py"
  echo "generated $out"
}

gen user
gen chat
gen vision
