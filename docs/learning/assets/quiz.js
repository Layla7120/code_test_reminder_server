// 재사용 퀴즈 위젯.
// 마크업:
//   <div class="quiz" data-answer="1">
//     <p class="q">질문</p>
//     <button>보기 0</button><button>보기 1</button>
//     <p class="fb">해설</p>
//   </div>
// 보기 텍스트 길이는 작성자가 맞춘다 — 길이가 곧 힌트가 되기 때문.
document.querySelectorAll('.quiz').forEach((quiz) => {
  const answer = Number(quiz.dataset.answer);
  const buttons = [...quiz.querySelectorAll('button')];
  const fb = quiz.querySelector('.fb');
  buttons.forEach((btn, i) => {
    btn.addEventListener('click', () => {
      buttons.forEach((b) => { b.disabled = true; });
      btn.classList.add(i === answer ? 'correct' : 'wrong');
      if (i !== answer) buttons[answer].classList.add('correct');
      if (fb) fb.classList.add('show');
    });
  });
});
