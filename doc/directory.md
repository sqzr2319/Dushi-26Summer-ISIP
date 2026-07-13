app/src/main/java/com/example/photoagent/
│
├── data/                                    # 数据层
│   └── model/
│       ├── Photo.kt                         # 照片实体 (全体)
│       ├── ImageAnalysisResult.kt           # 分析结果数据 (全体)
│       ├── OrganizationPlan.kt              # 整理方案数据 (全体)
│       └── SearchResult.kt                  # 检索结果数据 (全体)
│
├── domain/                                  # 业务逻辑层
│   ├── skill/                               # Skills目录
│       ├── AnalyzeImageSkill.kt             # Skill 1: 图片内容理解 (李佳乔)
│       ├── GenerateStrategySkill.kt         # Skill 2: 整理策略生成 (李佳乔)
│       ├── SearchPhotosSkill.kt             # Skill 3: 模糊检索 (孙长毅)
│       ├── Skill.kt                         # Skill统一接口 (杨祺瀚)
│       └── SkillRegistry.kt                 # Skill注册表 (杨祺瀚)
│
├── ai/                                      # AI引擎层
│   ├── PromptManager.kt                     # System Prompt管理 (杨祺瀚)
│   ├── InferenceEngine.kt                   # 推理引擎接口 (杨祺瀚)
│   ├── InferenceEngineImpl.kt               # 推理引擎实现 (杨祺瀚)
│   ├── ModelLoader.kt                       # 模型加载器 (杨祺瀚)
│   └── ModelConfig.kt                       # 模型配置 (杨祺瀚)
│
├── ui/                                      # 表现层 (褚一枫)
│   ├── MainActivity.kt                      # 主Activity
│   ├── gallery/                             # 相册展示
│   │   ├── PhotoGridFragment.kt             # 照片网格
│   │   └── PhotoGridAdapter.kt              # 网格适配器
│   ├── search/                              # 搜索界面
│   │   ├── SearchFragment.kt                # 搜索页
│   │   └── SearchResultAdapter.kt           # 搜索结果适配器
│   └── organize/                            # 整理界面
│       ├── OrganizationFragment.kt          # 整理结果展示
│       └── DuplicateConfirmDialog.kt        # 重复确认对话框
│
└── utils/                                   # 工具类 (杨祺瀚)
    ├── ImageUtils.kt                        # 图片处理工具
    ├── FileUtils.kt                         # 文件操作工具
    └── JsonParser.kt                        # JSON解析工具
