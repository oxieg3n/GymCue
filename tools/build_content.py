"""Convert GymGuide_Content.xlsx -> app/src/main/assets/content.json
Usage: python tools/build_content.py GymGuide_Content.xlsx"""
import sys, json, openpyxl
L = lambda v: [x.strip() for x in str(v or "").replace("\n", ";").split(";") if x.strip()]
S = lambda v: str(v).strip() if v is not None else ""
wb = openpyxl.load_workbook(sys.argv[1] if len(sys.argv) > 1 else "GymGuide_Content.xlsx", data_only=True)
def rows(name):
    if name not in wb.sheetnames: return []
    ws = wb[name]; hdr = [c.value for c in ws[1]]
    return [dict(zip(hdr, r)) for r in ws.iter_rows(min_row=3, values_only=True) if r and r[0]]
eq = [dict(id=r["equipment_id"], name=r["machine_name"], manufacturer=S(r.get("manufacturer_model")),
      alternateNames=L(r.get("alternate_names")), category=S(r.get("category")), photo=S(r.get("photo_file")),
      howToUse=L(r.get("how_to_use_steps")), qrCode=S(r.get("qr_code")), videoUrl=S(r.get("video_url")), notes=S(r.get("notes")))
      for r in rows("Equipment") if str(r.get("available_at_my_gym") or "Y").upper() != "N"]
setups = {}
for r in rows("Exercise Setup"):
    setups.setdefault(r["exercise_id"], []).append(dict(equipmentId=S(r.get("equipment_id")), position=S(r.get("position")),
        angle=S(r.get("angle")), attachment=S(r.get("attachment")), notes=L(r.get("setup_notes"))))
ex = [dict(id=r["exercise_id"], name=r["exercise_name"], equipmentIds=L(r["equipment_ids"]),
      primaryMuscles=L(r["primary_muscles"]), secondaryMuscles=L(r.get("secondary_muscles")),
      movement=S(r["movement_category"]), difficulty=S(r.get("difficulty")) or "Beginner", setup=L(r.get("setup_steps")),
      steps=L(r.get("movement_steps")), breathing=S(r.get("breathing_cue")), mistakes=L(r.get("common_mistakes")),
      safety=S(r.get("safety_notes")), alternativeIds=L(r.get("alternative_exercise_ids")), media=S(r.get("photo_video_file")),
      equipmentSetup=setups.get(r["exercise_id"], []))
      for r in rows("Exercises")]
tp = {}
for r in rows("Workout Templates"):
    t = tp.setdefault(r["template_id"], dict(id=r["template_id"], name=r["template_name"], split=r["split_type"],
        description=S(r.get("description")), items=[]))
    t["items"].append(dict(order=int(r["order"]), exerciseId=r["exercise_id"], sets=int(r["sets"]), reps=str(r["reps"]), restSec=int(r["rest_seconds"])))
for t in tp.values():
    t["items"] = [{k: v for k, v in i.items() if k != "order"} for i in sorted(t["items"], key=lambda i: i["order"])]
ids = {e["id"] for e in eq}; xids = {e["id"] for e in ex}; errs = []
for e in ex:
    errs += [f"{e['id']}: unknown equipment {i}" for i in e["equipmentIds"] if i not in ids]
    errs += [f"{e['id']}: unknown alternative {i}" for i in e["alternativeIds"] if i not in xids]
    errs += [f"{e['id']}: setup row for {s['equipmentId']} which this exercise doesn't use" for s in e["equipmentSetup"] if s["equipmentId"] not in e["equipmentIds"]]
errs += [f"Exercise Setup: unknown exercise {k}" for k in setups if k not in xids]
for t in tp.values(): errs += [f"{t['id']}: unknown exercise {i['exerciseId']}" for i in t["items"] if i["exerciseId"] not in xids]
if errs: print("\n".join(errs)); sys.exit(1)
json.dump(dict(equipment=eq, exercises=ex, templates=list(tp.values())), open("app/src/main/assets/content.json", "w", encoding="utf-8"), indent=1, ensure_ascii=False)
print(f"OK: {len(eq)} equipment, {len(ex)} exercises, {len(tp)} templates")
