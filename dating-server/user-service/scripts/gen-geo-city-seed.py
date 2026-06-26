#!/usr/bin/env python3
"""
gen-geo-city-seed.py
====================
将 SimpleMaps US Cities Basic CSV 转成 Flyway 迁移文件
`src/main/resources/db/migration/V4__seed_geo_city.sql`,灌入 ~28k 行美国城市数据。

一次性脚本,不进运行时;CSV 不入仓(`.gitignore` 屏蔽 `scripts/uscities.csv`),
生成的 V4 SQL 入仓。再次刷新字典只需重跑本脚本,Git diff 即可看出哪些城市变化。

数据源
------
SimpleMaps US Cities Basic — https://simplemaps.com/data/us-cities
许可:CC-BY 4.0 —— 公网入口需 attribution 链接(由产品/前端在 App About / 关于页面承接)。

使用步骤
--------
1. 浏览器打开 https://simplemaps.com/data/us-cities,下载 Basic 版 zip,
   解压拿到 `uscities.csv`(~1.5 MB)
2. 把 `uscities.csv` 放到当前目录:
       user-service/scripts/uscities.csv
3. 在仓库根目录运行:
       python3 user-service/scripts/gen-geo-city-seed.py
4. 生成产物:
       user-service/src/main/resources/db/migration/V4__seed_geo_city.sql
   提交进仓库,Flyway 下次启动自动灌入

CSV 字段(SimpleMaps Basic)
---------------------------
city, city_ascii, state_id, state_name, county_fips, county_name,
lat, lng, population, density, source, military, incorporated,
timezone, ranking, zips, id

我们只取:city / state_id / state_name / lat / lng / population / id
"""

import csv
import os
import sys
from pathlib import Path

# ---------------------------------------------------------------- 路径常量
SCRIPT_DIR = Path(__file__).resolve().parent
CSV_PATH = SCRIPT_DIR / "uscities.csv"
SQL_PATH = SCRIPT_DIR.parent / "src" / "main" / "resources" / "db" / "migration" / "V4__seed_geo_city.sql"

BATCH_SIZE = 500  # 每条 INSERT 携带 500 行,平衡 SQL 行数和单语句大小


# ---------------------------------------------------------------- SQL 转义
def sql_str(s: str) -> str:
    """PostgreSQL 单引号字符串字面量;内部 ' 转 ''。"""
    if s is None:
        return "NULL"
    return "'" + str(s).replace("'", "''") + "'"


def sql_num(s: str) -> str:
    """数值字段;空串 / None → NULL,否则原样。"""
    if s is None or s == "":
        return "NULL"
    return str(s)


# ---------------------------------------------------------------- 主流程
def main() -> int:
    if not CSV_PATH.exists():
        print(f"ERROR: {CSV_PATH} not found.", file=sys.stderr)
        print("Download Basic CSV from https://simplemaps.com/data/us-cities", file=sys.stderr)
        print(f"and place it at: {CSV_PATH}", file=sys.stderr)
        return 1

    rows = []
    seen = set()  # 防 (city, state_id) 重复
    with CSV_PATH.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            city = (row.get("city") or "").strip()
            state_id = (row.get("state_id") or "").strip().upper()
            state_name = (row.get("state_name") or "").strip()
            lat = (row.get("lat") or "").strip()
            lng = (row.get("lng") or "").strip()
            population = (row.get("population") or "").strip()
            source_id = (row.get("id") or "").strip()

            if not city or not state_id or not state_name or not lat or not lng:
                continue
            key = (city.lower(), state_id)
            if key in seen:
                continue
            seen.add(key)
            rows.append((city, state_id, state_name, lat, lng, population, source_id))

    if not rows:
        print("ERROR: no valid rows parsed from CSV", file=sys.stderr)
        return 1

    rows.sort(key=lambda r: (r[1], r[0].lower()))  # 按 state_id, city 排序;diff 友好

    SQL_PATH.parent.mkdir(parents=True, exist_ok=True)
    with SQL_PATH.open("w", encoding="utf-8") as out:
        out.write(_header(len(rows)))
        for batch_start in range(0, len(rows), BATCH_SIZE):
            batch = rows[batch_start:batch_start + BATCH_SIZE]
            out.write(
                "INSERT INTO geo_city (city, state_code, state_name, country_code, lat, lng, population, source_id) VALUES\n"
            )
            values = []
            for city, state_id, state_name, lat, lng, population, source_id in batch:
                values.append(
                    f"  ({sql_str(city)}, {sql_str(state_id)}, {sql_str(state_name)}, 'US', "
                    f"{sql_num(lat)}, {sql_num(lng)}, {sql_num(population)}, {sql_str(source_id)})"
                )
            out.write(",\n".join(values))
            out.write("\nON CONFLICT (city, state_code, country_code) DO NOTHING;\n\n")

    print(f"Wrote {len(rows)} rows → {SQL_PATH}")
    return 0


def _header(row_count: int) -> str:
    return f"""-- =====================================================================
-- V4__seed_geo_city.sql
-- 灌入 geo_city 字典(~{row_count} 行,SimpleMaps US Cities Basic)
--
-- 由 user-service/scripts/gen-geo-city-seed.py 自动生成 —— 不要手改,
-- 字典刷新重跑脚本即可(脚本顶部注释有步骤)。
--
-- 许可:CC-BY 4.0,公网入口需 attribution 链接(由产品/前端承接)。
-- ON CONFLICT DO NOTHING:重跑幂等,已有 (city, state_code, US) 不动。
-- =====================================================================

SET TIME ZONE 'UTC';

"""


if __name__ == "__main__":
    sys.exit(main())
