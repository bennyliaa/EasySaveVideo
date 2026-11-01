import fs from 'node:fs/promises';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {Presentation,PresentationFile} from 'file:///C:/Users/benny/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/@oai/artifact-tool/dist/artifact_tool.mjs';
const root='D:/CodexProjects/EasySaveVideo';
const build=path.join(root,'.ui-ppt-build');
const out=path.join(root,'docs/ui');
const skill='C:/Users/benny/.codex/plugins/cache/openai-primary-runtime/presentations/26.1009.11318/skills/presentations';
const generated='C:/Users/benny/.codex/generated_images/01a12aa3-a0e4-75d3-bbc7-f1bfb098f914';
const assets=path.join(root,'docs/UI_Concept_v0.1');
await fs.mkdir(out,{recursive:true});await fs.mkdir(path.join(build,'previews'),{recursive:true});
const p=Presentation.create({slideSize:{width:1280,height:720}});
const C={bg:'#F5F8FA',ink:'#142B36',muted:'#546D79',accent:'#087F8C'};
const font='Microsoft YaHei';
function txt(s,t,x,y,w,h,size=22,color=C.ink,bold=false){
 const q=s.shapes.add({geometry:'textbox',position:{left:x,top:y,width:w,height:h},fill:'none',line:{fill:'none',width:0}});
 q.text=t;q.text.style={typeface:font,fontSize:size,color,bold};return q;
}
function shell(title,sub){const s=p.slides.add();s.background.fill=C.bg;const n=p.slides.items.length;
 txt(s,String(n).padStart(2,'0'),42,30,60,42,22,C.accent,true);
 txt(s,title,110,24,1120,58,32,C.ink,true);
 if(sub)txt(s,sub,110,82,1100,40,18,C.muted);
 txt(s,'ESV UI 效果参考 V1.0    概念界面，示例数据    2026-10-11',42,676,1120,24,11,C.muted);
 txt(s,String(n),1200,676,38,24,12,C.accent);
 s.speakerNotes.text='依据：ESV v0.3_CN规格、用户确认的UI效果图、SkinKit第三方适配Kit。参考PPT结构：LocalVideosBrowser_UI_Effect_Design_V1.1_FINAL_2026-10-11.pptx。此页是视觉与交互验收目标，不证明功能已实现。';return s;
}
async function pic(s,file,rect,frame,alt){const b=await fs.readFile(file);s.images.add({blob:b,contentType:'image/png',fit:'contain',position:frame,alt,
 ...(rect?{crop:{left:rect[0]/1536,top:rect[1]/1024,right:(1536-rect[2])/1536,bottom:(1024-rect[3])/1024}}:{})});}
function notes(s,items,x=840,y=165,w=385){items.forEach((it,i)=>{txt(s,it[0],x,y+i*155,w,38,23,C.accent,true);txt(s,it[1],x,y+46+i*155,w,96,20,C.ink);});}
function flat(title,sub,blocks){const s=shell(title,sub);blocks.forEach((b,i)=>{let x=60+(i%2)*610,y=150+Math.floor(i/2)*165;txt(s,b[0],x,y,560,42,24,C.accent,true);txt(s,b[1],x,y+49,560,100,20);});return s;}
function table(title,sub,values,widths){const s=shell(title,sub);const tb=s.tables.add({rows:values.length,columns:values[0].length,left:50,top:145,width:1180,height:470,values,columnWidths:widths});
 for(let r=0;r<values.length;r++)for(let c=0;c<values[0].length;c++){const cell=tb.getCell(r,c);cell.fill=r===0?'#DCEEF0':r%2?'#FFFFFF':'#EDF3F5';cell.text.style={typeface:font,fontSize:r===0?21:19,color:C.ink,bold:r===0};}
 return s;}
const img={
 discovery:path.join(generated,'exec-83844dd0-6118-417b-972c-7be619823ef0.png'),browser:path.join(generated,'exec-c3683d56-ee92-4919-874a-a6d120476af1.png'),tasks:path.join(generated,'exec-fd4bccd2-4eb2-4169-830f-21bac46078d0.png'),record:path.join(generated,'exec-d096c988-607f-4b3c-934e-c73be3765d1f.png'),library:path.join(generated,'exec-e2226f19-7782-4dbb-9576-fcec7780eb55.png'),settings:path.join(generated,'exec-723667cb-9406-4452-aceb-bfedc4755547.png'),rights:path.join(generated,'exec-c9987262-69e0-472a-b0d4-57a1080ccaaa.png'),dark:path.join(generated,'exec-8a04cd3c-6732-419a-aef1-8ef87b03f7e5.png'),english:path.join(generated,'exec-c55883bf-7aea-43b0-99a8-656875eb2b07.png')};
const crop={
 discovery:[[20,171,349,919],[359,171,693,919],[700,173,1522,919]],
 browser:[[20,172,346,958],[356,173,660,958],[674,173,1523,958]],
 tasks:[[30,186,353,929],[361,188,671,929],[681,191,1500,932]],
 record:[[30,192,347,901],[360,191,686,903],[699,209,1513,846]],
 library:[[24,171,347,889],[378,173,679,885],[701,173,1525,887]],
 settings:[[26,214,327,892],[337,214,629,891],[642,214,1511,891]],
 rights:[[24,146,355,965],[369,145,696,965],[710,145,1503,955]],
 dark:[[20,154,351,923],[358,154,692,923],[699,154,1524,923]],
 english:[[20,172,350,921],[359,172,692,921],[700,171,1521,921]]};
{
const s=p.slides.add();s.background.fill=C.bg;
txt(s,'EasySaveVideo',55,90,620,85,49,C.ink,true);txt(s,'UI 效果设计与验收参考',55,200,560,100,38,C.accent,true);
txt(s,'手机与平板\n浅色与深色\n国际化与真实／模拟功能接入',55,335,530,140,25,C.muted);
txt(s,'V1.0  ·  2026-10-11\n交付对象：Coder / Reviewer / Tester',55,568,530,68,19,C.muted);
await pic(s,img.discovery,crop.discovery[2],{left:625,top:140,width:610,height:450},'ESV平板发现页概念效果');
s.speakerNotes.text='本PPT参照用户提供的LVB效果设计PPT结构编写。ESV视觉沿用用户认可的UI_Concept_v0.1蓝绿色效果图。界面图为生成式概念图，精确业务文案和统一导航以第34页校正及页面正文为准。';}
flat('文档用途与验收边界','UI 实现是新增里程碑，功能开发继续使用现有计划',[
 ['效果参考','Coder用本文件实现页面结构、信息层级、组件和窗口适配。'],
 ['已开发功能','连接现有接口，使用真实数据、权限和任务状态。'],
 ['未开发功能','预留完整页面与交互位置，用明确标识的模拟数据演示。'],
 ['验收边界','UI验收检查外观和交互。模拟成功不代表保存、加密或录制功能通过。'],
 ['优先级','页面正文及校正页统一文案、导航和业务边界，图片提供视觉目标。'],
 ['图像范围','设备边框及板外宣传背景属于展示包装，不进入App页面。']]);
table('功能页面覆盖','七个板块覆盖手机与平板，详细效果按页面分别展示',[
 ['板块','页面与交互','平板重点'],['发现','新标签、搜索结果、来源与四策略','网格与筛选侧栏'],['浏览与保存','原网页、标签、候选与清晰度','网页与保存选项分栏'],['任务','任务列表、详情、恢复、导出','列表与任务详情'],['录制与编辑','授权准备、暂停片段、裁切和压缩','预览与编辑检查器'],['媒体与私密','文件夹、视频管理、保险箱','网格与文件详情'],['设置与来源','偏好、历史清理、来源维护','设置分类与详情'],['权益与异常','功能边界、空结果、故障恢复','错误状态与操作出口']], [180,620,380]);
{
const s=shell('浅色视觉基线','蓝绿色主操作，中性表面和柔和圆角');await pic(s,img.discovery,null,{left:45,top:130,width:790,height:520},'手机及平板浅色效果');
notes(s,[['默认主题','保留蓝绿色品牌候选。正文与内容表面以中性色为主。'],['公共组件','圆角、间距、字号和状态来自SkinKit Token，页面不独立定义。'],['内容呈现','视频缩略图保留原色。图片缺失使用占位，未知元数据不伪造。']]);}
{
const s=shell('深色视觉基线','独立设计颜色与层级，保持同一页面结构');await pic(s,img.dark,null,{left:45,top:130,width:790,height:520},'手机及平板深色效果');
notes(s,[['表面层级','深底色、略亮内容表面、柔和分隔，文字保持可读。'],['主题模式','支持跟随系统、浅色、深色。主题切换只改变外观。'],['状态验收','按钮、输入、选择、错误和禁用态均需检查。图片不简单反色。']]);}
const pages=[
 ['discovery',0,'手机新标签页','地址访问与关键词发现保持独立',[
 ['地址与搜索','永久地址栏接受网址。关键词输入进入发现流程，用户可以纠正输入类型。'],['快捷入口','历史、收藏、无痕以及本地最近搜索。清理历史不删除视频。'],['统一导航','发现、任务、录制、媒体库四项。设置位于顶部，入口始终可达。']]],
 ['discovery',1,'手机搜索结果','两列视频卡片与稳定分页',[
 ['媒体卡','缩略图、标题、来源、已知时长。点击打开第三方原网页。'],['分页','展示已物化页，未知总量不显示虚假尾页。默认每页20，可配置。'],['增量结果','异步补充不移动当前阅读卡片。来源耗尽时显示明确结束状态。']]],
 ['discovery',2,'平板发现与筛选','同一搜索上下文，多栏展示',[
 ['布局','侧边导航、视频网格、筛选侧栏。宽度不足时收起侧栏。'],['四种策略','优先新视频、隐藏已看过、隐藏已展示、显示全部。准确名称见校正页。'],['资源边界','离开发现或退后台停止补充。已发起保存任务独立管理。']]],
 ['browser',0,'手机原网页浏览','网页内容与ESV控制区域清楚区分',[
 ['浏览器','地址栏、前进后退、刷新、多标签与原网页播放复用现有Browser Host。'],['保存入口','轻量检测发现媒体后提供保存／录制。完整分析由用户点击触发。'],['页面归属','第三方网页的内容和外观不归SkinKit控制，不强制改写网页主题。']]],
 ['browser',1,'视频与清晰度选择','选择媒体、质量、目的地和保存方式',[
 ['候选确认','多视频由用户选择。未知文件大小显示“大小未知”。'],['方法可见','①直接下载免费。②流媒体保存与③录制保存属于Save Pro。'],['提交操作','开始保存可取消。③先用户确认再系统授权，不自动启动。']]],
 ['browser',2,'平板浏览与保存','网页播放与保存检查器并排',[
 ['浏览空间','保留标签栏和地址栏，网页独立占据主要内容区域。'],['保存侧栏','使用同一候选选择状态，避免手机与平板分别实现保存逻辑。'],['正确降级','取消、满盘或受保护内容不会自动换到下一保存方式。']]],
 ['tasks',0,'手机任务中心','状态、当前方法和恢复操作始终可见',[
 ['任务状态','分析、执行、等待用户、处理、完成、失败、取消分别表达。'],['进度表达','已知进度显示百分比。整理与验证等未知进度使用阶段说明。'],['恢复出口','登录失效回原网页，任务中断核验后继续，用户可取消。']]],
 ['tasks',1,'手机任务详情','下载、验证与入库分阶段展示',[
 ['状态来源','使用任务Repository的权威状态，界面不自行推断成功。'],['完成条件','产物验证并提交媒体库后才显示完成，字节下载结束仍可能处理中。'],['后台限制','显示系统限制。切页与主题切换不取消已启动保存任务。']]],
 ['tasks',2,'平板任务与恢复','任务列表、详情与恢复通知并存',[
 ['共享上下文','选中任务ID保持稳定，窗口变化不会产生第二个任务。'],['导出独立','导出失败保留库内原件，提供重新选择目标目录。'],['恢复提示','启动检查只展示可操作结果。进度未知时不编造百分比。']]],
 ['record',0,'录制准备与授权','整屏捕获范围和音频能力先说明',[
 ['捕获范围','整屏录制捕获当前可见内容，不承诺隐藏标签继续独立捕获。'],['音频','内部音频由系统与内容决定。不可用时说明，麦克风默认关闭。'],['高级能力','框选及片段能力按真实实现与权益开放，其余使用标识清楚的模拟界面。']]],
 ['record',1,'高级录制与有效时长','暂停期间不计入成片',[
 ['控制操作','开始、暂停、继续、停止使用串行且幂等的现有录制接口。'],['片段表达','示例20秒＋30秒合计50秒。暂停等待不计入有效时长。'],['中断恢复','保留有效片段并提示，再次录制需要新系统授权。']]],
 ['record',2,'平板裁切与视频编辑','预览区、片段条和检查器',[
 ['编辑组合','裁切手柄及片段条属于业务组件，按钮与切换控件复用Material3。'],['输出安全','原质量或节省空间由用户选择，编辑生成新视频并保留原件。'],['商品边界','纯编辑只需工具权益，高级录制另需基础录制权益。']]],
 ['library',0,'手机媒体库与文件夹','本地视频管理与多选操作',[
 ['基本信息','名称、已知时长、大小、分辨率与时间，支持播放及文件夹管理。'],['多选','选择后出现移动、分享、更多操作，结束选择后恢复普通导航。'],['文件保护','视频不是缓存，清理缓存或历史不删除用户媒体。']]],
 ['library',1,'免费私密保险箱','锁定状态无私密内容泄漏',[
 ['免费边界','认证、加密、自动锁永久免费，与购买状态无关。'],['锁定画面','不显示私密标题与缩略图，最近任务使用安全占位。'],['解锁','PIN／密码或系统生物识别。模拟解锁不宣称真正完成加密保护。']]],
 ['library',2,'平板媒体详情与导出','文件夹、网格及当前视频详情',[
 ['管理操作','播放、重命名、移动、移入私密空间，通过现有媒体接口执行。'],['明确导出','用户选择相册、文件或系统分享，导出与库内保存分别验收。'],['私密副本','导出前说明目标是普通文件，导出副本不受保险箱保护。']]],
 ['settings',0,'手机设置分类','常用偏好按业务领域分组',[
 ['设置结构','发现、保存、录制编辑、私密空间、隐私诊断、权益，并增加外观与语言。'],['配置存储','页面不直接读写存储，复用统一配置数据层。'],['清理说明','缓存、发现历史、来源进度分别清理，默认不上传错误日志。']]],
 ['settings',1,'搜索来源管理','启停、更新、版本和安全回退',[
 ['来源示例','A／B／C及版本仅示例，不代表最终接入站点或免费数量。'],['局部隔离','单来源不可用不影响其它来源、地址访问或通用保存。'],['版本安全','失败保留可用旧版，回退必须满足验签与安全版本规则。']]],
 ['settings',2,'平板设置与来源详情','分类、参数和来源状态并排',[
 ['发现参数','每行、每页数量及省电／平衡／积极模式，变化范围明确。'],['历史策略','四策略按本地历史语义执行，修改不删除现有文件。'],['常驻能力','没有可用来源时仍可输入网址。外观和语言在同一设置体系中扩展。']]],
 ['rights',0,'功能权益说明','免费能力与三个付费模块',[
 ['免费能力','浏览器、①直接下载、媒体库、私密保险箱，支付故障仍可用。'],['付费模块','Provider Plus、Save Pro、Video Tools Pro。总包属于未来规划。'],['价格边界','价格及购买方式尚未冻结，演示不展示真实交易成功。']]],
 ['rights',1,'无更多结果与空状态','明确原因并提供下一步',[
 ['结果结束','“当前来源暂无更多”，不循环制造无限加载。'],['可选动作','更换来源、调整筛选，或输入网址直接继续浏览。'],['稳定界面','保留当前上下文与可操作入口，不让单来源故障拖死页面。']]],
 ['rights',2,'异常状态与安全停止','存储、内容保护与音频不可用',[
 ['满盘','停止当前写入，释放空间后重试，可取消，不盲目转录屏。'],['受保护内容','明确无法保存并返回网页，不提供绕过入口。'],['音频与中断','用户决定仅录画面或取消，录制中断保留有效片段并重新授权。']]],
];
for(const [key,which,title,sub,items] of pages){const s=shell(title,sub);const r=crop[key][which];const ratio=(r[2]-r[0])/(r[3]-r[1]);let h=525,w=h*ratio;
 if(w>755){w=755;h=w/ratio;}
 await pic(s,img[key],r,{left:50+(755-w)/2,top:135+(525-h)/2,width:w,height:h},title+'概念UI');notes(s,items);
 s.speakerNotes.append(`\n视觉来源：${img[key]}。此图作为概念效果验收，统一导航及图中文字校正以第34页为准。每页真实/模拟绑定须在coder实施清单中登记，图片不作为当前功能完成证据。`);}
{
const s=shell('英文界面与长译文','同一组件与业务状态，文字通过语言资源替换');
await pic(s,img.english,crop.english[1],{left:40,top:138,width:220,height:514},'English discovery phone reference');
await pic(s,img.english,crop.english[2],{left:287,top:180,width:510,height:455},'English discovery tablet reference');
notes(s,[['语言支持','国际化纳入UI里程碑。简体中文＋英文为首期建议，范围待冻结。'],['布局扩展','英文策略名称允许换行，保持触控尺寸与阅读层级。'],['格式化','日期、时间、数字和复数按Locale处理，禁止拼接句子。']]);}
table('手机与平板布局规则','布局依据可用窗口、方向、分屏及折叠遮挡',[
 ['内容','窄窗口','宽窗口'],['主导航','四项底部导航，设置顶部入口','侧边导航，设置保持可达'],['发现','默认两列，筛选使用弹层','弹性网格与可收起筛选侧栏'],['原网页保存','WebView页面＋保存Sheet','WebView＋保存检查器'],['任务与媒体库','列表与详情分别打开','列表／网格与详情并排'],['编辑','预览、工具分区或Sheet','预览、片段条、侧面检查器'],['状态保持','保留页码、选中标识及阅读锚点','复用同一状态，不重建任务'],['无障碍','长译文、200%字体与触控区域','同样要求，禁止单纯拉伸']], [250,465,465]);
flat('SkinKit 与 Compose 实现边界','复用已有ESV主题层，完善标准组件映射',[
 ['主题职责','SkinKit解析主题契约，ESV将同一版本映射为Compose视觉模型。'],['默认回退','ESV完整Light/Dark AppDefaultTheme永久保留，离线可用。'],['标准控件','Material3承担按钮、输入、导航、弹窗等基础语义和交互。'],['专属组件','视频卡、保存选择、任务状态、录制控制和裁切条由ESV组合。'],['页面依赖','Feature读取类型化ESV视觉模型，不直接读取皮肤包或SDK实现。'],['生命周期','切换主题时，保留数据层、网页、下载和录制会话。']]);
table('真实功能与模拟功能接入','一套页面契约，按能力替换实现',[
 ['状态','实现要求','验收重点'],['已开发','接现有真实接口与状态','UI操作对应实际业务结果'],['部分开发','已完成能力真实接入，其余模拟','明确每个动作的数据来源'],['未开发','完整页面位置与模拟交互','模拟标识清楚，无真实副作用'],['演示数据','正常／加载／空／错误／中断','数据可重复，未知值不伪造'],['后续替换','统一接口、ViewModel与UiState','替换实现，不重写页面'],['隐私能力','模拟数据不使用真实私密媒体','假解锁、假加密不作安全证明'],['发行构建','无可任意解锁或模拟购买入口','占位入口策略按发行决定']], [230,495,455]);
flat('国际化与可访问性验收','语言、文字扩展和窗口变化共同验证',[
 ['文案资源','页面、错误、弹层、无障碍标签和模拟文案均使用语言资源。'],['语言切换','跟随系统或手动选择。切换及Activity重建不能打断任务。'],['伪本地化','检查长文本、截断、遗漏、复数和方向，预留RTL支持。'],['字体与触控','200%字体无关键操作裁切，Android触控目标至少48dp。'],['状态辨识','选中、错误、禁用、录制和暂停不只靠颜色表达。'],['对比度','实际Token与背景组合实测，普通文本以4.5:1为基准。']]);
table('UI 实现里程碑','新增UI工作包，衔接功能开发，不替代既有阶段编号',[
 ['工作包','交付','退出条件'],['UI-A 基线','功能接入清单、源码与依赖核对','正式目录、API30/31冲突及依赖准入明确'],['UI-B 主题组件','SkinKit、Material3、国际化基础','发现页与保存Sheet浅深色及大字体通过'],['UI-C 页面框架','导航、自适应布局、统一状态','手机／平板所有目的地可达'],['UI-D 功能接入','七板块真实／模拟绑定','已开发能力真实可用，模拟无误导'],['UI-E 验收','实机截图、差异记录、回归','关键流程与隐私/任务连续性检查完成']], [240,430,510]);
table('最终效果比对与验收记录','同条件截图比对，UI与功能结果分别记录',[
 ['维度','比对要求','记录内容'],['截图条件','相同窗口、语言、模式、字号、数据','设备／窗口／版本／数据集'],['结构与层级','导航、主操作、信息顺序及分栏一致','参考页码及实际截图'],['组件与主题','Token、圆角、间距、字号及状态统一','偏差位置与修复结论'],['交互状态','加载、空、错误、取消及恢复有出口','UI PASS/FAIL及复测'],['真实功能','授权、任务、文件及加密结果单独验证','真实/混合/模拟标识'],['隐私与持续任务','换肤/语言/旋转不丢任务或泄露','日志、缓存、锁定状态证据'],['合理差异','系统授权页、网页内容及动态媒体可不同','原因、影响与接受记录']], [250,530,400]);
flat('效果图校正与统一规则','以下规则优先于概念图片中的个别文字和图标差异',[
 ['导航与图标','统一四项：发现、任务、录制、媒体库。设置为顶部或侧栏入口。'],['品牌标记','统一ESV波浪标识候选。概念图中的播放三角标记不作为第二套品牌。'],['发现分页','建议默认20条／页。图中12为示例，默认设置不变。'],['历史策略','优先显示新视频／隐藏已看过的视频／隐藏所有已展示过的视频／显示全部视频。'],['英文与隐私','“无痕”译为Incognito。英文概念图中的Offline不代表新增离线入口。'],['尺寸与验收','按实际窗口与Token适配，允许长文字换行。图像像素不直接等于dp。']]);
await fs.writeFile(path.join(build,'slide-index.json'),JSON.stringify(p.slides.items.map((s,i)=>({number:i+1,id:s.id})),null,2));
for(let i=0;i<p.slides.items.length;i++){
 const b=await p.export({slide:p.slides.items[i],format:'png',scale:1});
 await fs.writeFile(path.join(build,'previews',String(i+1).padStart(2,'0')+'.png'),new Uint8Array(await b.arrayBuffer()));
 console.log('rendered '+(i+1));
}
const candidate=path.join(build,'candidate.pptx');await(await PresentationFile.exportPptx(p)).save(candidate);
const {finalizePresentation}=await import(pathToFileURL(path.join(skill,'container_tools/artifact_tool_utils.mjs')).href);
const final=path.join(out,'EasySaveVideo_UI_Effect_Design_V1.0_2026-10-11.pptx');
const result=await finalizePresentation({workspaceDir:root,candidatePath:candidate,finalPath:final,
 pythonExecutable:'C:/Users/benny/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe',
 integrityValidatorPath:path.join(skill,'container_tools/inspect_presentation_package_integrity.py'),
 layoutValidatorPath:path.join(skill,'container_tools/inspect_presentation_layout_geometry.py'),
 layoutArgs:['--expected-slide-size-emu','12192000,6858000','--validate-heading-fit',...[3,28,30,32,33].flatMap(n=>['--require-native-table-slide',String(n)])],
 requiredNativeTableOwnerSlides:[3,28,30,32,33],fontPolicy:{basis:'design',families:[font]},verifyArtifactToolImport:true,
 receiptPath:path.join(build,'validation.json')});
console.log(JSON.stringify({slides:p.slides.items.length,final,result}));
