document.addEventListener('DOMContentLoaded', () => {
    // 1. Initial Load Animation
    const animatedElements = document.querySelectorAll('.reveal');

    const observerOptions = {
        threshold: 0.1,
        rootMargin: '0px 0px -50px 0px'
    };

    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('active');
                observer.unobserve(entry.target);
            }
        });
    }, observerOptions);

    animatedElements.forEach(el => observer.observe(el));

    // 2. Parallax Effect for App Mockup
    const heroVisual = document.getElementById('heroVisual');
    const phoneFrame = document.querySelector('.app-phone-frame');
    const appCards = document.querySelectorAll('.app-card');
    const floatingElements = document.querySelectorAll('.mockup-float');

    if (heroVisual && window.innerWidth > 900) {
        document.addEventListener('mousemove', (e) => {
            const mouseX = e.clientX / window.innerWidth;
            const mouseY = e.clientY / window.innerHeight;

            // Smoother, very subtle professional rotation
            // Rotate Y: -6 to 6 degrees (Reduced from 12)
            // Rotate X: 6 to -6 degrees (Reduced from 12)
            const rotateY = -6 + (mouseX * 12);
            const rotateX = 6 - (mouseY * 12);

            if (phoneFrame) {
                phoneFrame.style.transform = `rotateY(${rotateY}deg) rotateX(${rotateX}deg)`;
            }

            // Parallax for internal cards (depth effect)
            appCards.forEach((card, index) => {
                const depth = 0.08 + (index * 0.02);
                const moveX = (mouseX - 0.5) * depth * 30;
                const moveY = (mouseY - 0.5) * depth * 30;
                card.style.transform = `translate3d(${moveX}px, ${moveY}px, 0)`;
            });

            // Parallax for floating elements
            floatingElements.forEach((el, index) => {
                const depth = 0.15 + (index * 0.05);
                const moveX = (mouseX - 0.5) * depth * -50; // Inverse movement
                const moveY = (mouseY - 0.5) * depth * -50;
                el.style.transform = `translate3d(${moveX}px, ${moveY}px, 0)`;
            });
        });
    }

    // 3. Dynamic Island Interaction (Fun Micro-interaction)
    const island = document.querySelector('.dynamic-island');
    if (island) {
        island.addEventListener('click', () => {
            island.style.width = '200px';
            island.style.height = '40px';
            setTimeout(() => {
                island.style.width = '90px';
                island.style.height = '26px';
            }, 2000);
        });
    }

    // 4. Mockup Data Interactivity
    const timeValueElement = document.querySelector('.hero-time-value');
    const trendBars = document.querySelectorAll('.trend-bar');
    const selectorOptions = document.querySelectorAll('.selector-option');

    const dataMap = {
        'Week': {
            time: '12h 45m',
            heights: ['40%', '70%', '50%', '100%', '60%', '80%', '40%']
        },
        'Month': {
            time: '48h 20m',
            heights: ['60%', '40%', '70%', '50%', '90%', '30%', '60%']
        },
        'Year': {
            time: '340h 10m',
            heights: ['30%', '50%', '80%', '60%', '40%', '90%', '70%']
        }
    };

    if (selectorOptions.length > 0) {
        selectorOptions.forEach(option => {
            option.addEventListener('click', () => {
                selectorOptions.forEach(opt => opt.classList.remove('active'));
                option.classList.add('active');

                const period = option.textContent;
                const data = dataMap[period];

                if (data) {
                    if (timeValueElement) {
                        timeValueElement.style.opacity = '0';
                        setTimeout(() => {
                            timeValueElement.textContent = data.time;
                            timeValueElement.style.opacity = '1';
                        }, 200);
                    }

                    trendBars.forEach((bar, index) => {
                        bar.style.height = '4px'; // Min height
                        setTimeout(() => {
                            bar.style.height = data.heights[index];
                        }, 100 + (index * 30));
                    });
                }
            });
        });
    }

    // 5. Navbar Blur & Smooth Scroll
    const navbar = document.querySelector('.navbar');
    window.addEventListener('scroll', () => {
        if (window.scrollY > 20) {
            navbar.classList.add('scrolled');
        } else {
            navbar.classList.remove('scrolled');
        }
    });

    document.querySelectorAll('a[href^="#"]').forEach(anchor => {
        anchor.addEventListener('click', function (e) {
            e.preventDefault();
            const targetId = this.getAttribute('href');
            if (targetId === '#') return;
            const targetElement = document.querySelector(targetId);
            if (targetElement) {
                targetElement.scrollIntoView({
                    behavior: 'smooth',
                    block: 'start'
                });
            }
        });
    });
});
