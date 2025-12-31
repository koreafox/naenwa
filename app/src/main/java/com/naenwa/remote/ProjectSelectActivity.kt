package com.naenwa.remote

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.naenwa.remote.auth.GitHubAuthManager
import kotlinx.coroutines.launch

class ProjectSelectActivity : AppCompatActivity() {

    private lateinit var gitHubAuthManager: GitHubAuthManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressView: View
    private lateinit var emptyView: View
    private lateinit var fabNewProject: FloatingActionButton

    private val repositories = mutableListOf<GitHubAuthManager.Repository>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gitHubAuthManager = GitHubAuthManager(this)

        if (!gitHubAuthManager.isLoggedIn) {
            startActivity(Intent(this, GitHubLoginActivity::class.java))
            finish()
            return
        }

        setupUI()
        loadRepositories()
    }

    private fun setupUI() {
        // 간단한 레이아웃 동적 생성
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A1A.toInt())
            setPadding(0, 100, 0, 0)
        }

        // 헤더
        val header = TextView(this).apply {
            text = "프로젝트 선택"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(48, 32, 48, 8)
        }
        layout.addView(header)

        // 부제
        val subtitle = TextView(this).apply {
            text = "GitHub 저장소를 선택하거나 새 프로젝트를 만드세요"
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            setPadding(48, 0, 48, 32)
        }
        layout.addView(subtitle)

        // 로딩 인디케이터
        progressView = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        layout.addView(progressView, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            topMargin = 100
        })

        // 빈 상태
        emptyView = TextView(this).apply {
            text = "저장소가 없습니다\n새 프로젝트를 만들어보세요!"
            textSize = 16f
            setTextColor(0xFF666666.toInt())
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }
        layout.addView(emptyView, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 100
        })

        // RecyclerView
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ProjectSelectActivity)
            adapter = RepositoryAdapter()
            visibility = View.GONE
        }
        layout.addView(recyclerView, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        // 새 프로젝트 버튼
        val newProjectButton = MaterialButton(this).apply {
            text = "+ 새 프로젝트 만들기"
            setOnClickListener { showNewProjectDialog() }
        }
        layout.addView(newProjectButton, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(48, 16, 48, 48)
        })

        setContentView(layout)
    }

    private fun loadRepositories() {
        lifecycleScope.launch {
            progressView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.GONE

            val repos = gitHubAuthManager.getRepositories()
            repositories.clear()
            repositories.addAll(repos)

            progressView.visibility = View.GONE

            if (repos.isEmpty()) {
                emptyView.visibility = View.VISIBLE
            } else {
                recyclerView.visibility = View.VISIBLE
                recyclerView.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun showNewProjectDialog() {
        val input = EditText(this).apply {
            hint = "프로젝트 이름"
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("새 프로젝트")
            .setMessage("GitHub에 새 저장소를 만들고 프로젝트를 시작합니다.")
            .setView(input)
            .setPositiveButton("만들기") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    createNewProject(name)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun createNewProject(name: String) {
        lifecycleScope.launch {
            progressView.visibility = View.VISIBLE

            val result = gitHubAuthManager.createRepository(name, "Created with Naenwa", false)
            result.fold(
                onSuccess = { repo ->
                    Toast.makeText(this@ProjectSelectActivity, "저장소 생성 완료!", Toast.LENGTH_SHORT).show()
                    selectRepository(repo)
                },
                onFailure = { e ->
                    Toast.makeText(this@ProjectSelectActivity, "생성 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )

            progressView.visibility = View.GONE
        }
    }

    private fun selectRepository(repo: GitHubAuthManager.Repository) {
        // 선택한 저장소 정보 저장
        getSharedPreferences("naenwa", MODE_PRIVATE).edit()
            .putString("selected_repo_name", repo.name)
            .putString("selected_repo_url", repo.cloneUrl)
            .putString("selected_repo_full_name", repo.fullName)
            .apply()

        // 메인 화면으로 이동
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("repo_name", repo.name)
            putExtra("repo_url", repo.cloneUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    inner class RepositoryAdapter : RecyclerView.Adapter<RepositoryAdapter.ViewHolder>() {

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val nameText: TextView = itemView.findViewWithTag("name")
            val descText: TextView = itemView.findViewWithTag("desc")
            val privateText: TextView = itemView.findViewWithTag("private")
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val card = MaterialCardView(parent.context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(48, 16, 48, 16)
                }
                radius = 16f
                cardElevation = 4f
                setCardBackgroundColor(0xFF2A2A2A.toInt())
                isClickable = true
                isFocusable = true
            }

            val layout = android.widget.LinearLayout(parent.context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(32, 24, 32, 24)
            }

            val nameText = TextView(parent.context).apply {
                tag = "name"
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
            }
            layout.addView(nameText)

            val descText = TextView(parent.context).apply {
                tag = "desc"
                textSize = 13f
                setTextColor(0xFF888888.toInt())
            }
            layout.addView(descText)

            val privateText = TextView(parent.context).apply {
                tag = "private"
                textSize = 11f
                setTextColor(0xFF666666.toInt())
            }
            layout.addView(privateText)

            card.addView(layout)
            return ViewHolder(card)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val repo = repositories[position]
            holder.nameText.text = repo.name
            holder.descText.text = repo.description.ifEmpty { "설명 없음" }
            holder.privateText.text = if (repo.isPrivate) "🔒 Private" else "🌐 Public"

            holder.itemView.setOnClickListener {
                selectRepository(repo)
            }
        }

        override fun getItemCount() = repositories.size
    }
}
